package org.quiltmc;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.google.gson.*;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.utils.Pair;

import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.Array;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;

/**
 * Handler for requests to Lambda function.
 */
@SuppressWarnings("unused")
public class Meta implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {
    private static final DateFormat ISO_8601 = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ");

    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private final MavenRepository maven = new MavenRepository(System.getenv("META_MAVEN_URL"));
    private final String group = System.getenv("META_GROUP");
    private final Map<String, JsonArray> arrays = new ConcurrentHashMap<>();
    private final Map<String, JsonElement> launcherMetaData = new ConcurrentHashMap<>();
    private final Map<String, JsonObject> gameIntermediaries = new ConcurrentHashMap<>();
    private final Deque<MavenRepository.ArtifactMetadata.Artifact> loaderVersions = new ConcurrentLinkedDeque<>();
    private final Map<String, Pair<byte[], String>> files = new ConcurrentHashMap<>();

    public APIGatewayProxyResponseEvent handleRequest(final APIGatewayProxyRequestEvent input, final Context context) {
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("X-Custom-Header", "application/json");

        APIGatewayProxyResponseEvent response = new APIGatewayProxyResponseEvent()
                .withHeaders(headers);

        try {
            ExecutorService executor = Executors.newCachedThreadPool();

            CompletableFuture.allOf(
                    CompletableFuture.runAsync(this::populateMappings, executor),
                    CompletableFuture.runAsync(this::populateYarn, executor),
                    CompletableFuture.runAsync(this::populateInstaller, executor),
                    CompletableFuture.runAsync(this.populateLoader(executor), executor),
                    this.populateIntermediaryAndGame(executor)
            ).join();

            System.out.println("Building loader stuff");

            this.populateLoaderVersions();
            this.populateProfiles();

            JsonObject versions = new JsonObject();

            versions.add("game", this.arrays.get("game"));
            versions.add("mappings", this.arrays.get("mappings"));
            versions.add("intermediary", this.arrays.get("intermediary"));
            versions.add("loader", this.arrays.get("loader"));
            versions.add("installer", this.arrays.get("installer"));

            upload("v3/versions", this.gson.toJson(versions));
            upload("v3/versions/game", this.gson.toJson(this.arrays.get("game")));

            this.upload();

            System.out.println("Done updating files");

            return response
                    .withStatusCode(200)
                    .withBody(this.gson.toJson(versions));
        } catch (Exception e) {
            e.printStackTrace();

            return response
                    .withBody(String.format("{\"message\": \"%s\"}", e.toString()))
                    .withStatusCode(500);
        }
    }

    private void populateMappings()  {
        try {
            JsonArray mappings = toJson(this.maven.getMetadata(this.group, "yarn"),
                    version -> new JsonPrimitive(stripInfo(version.version)));
            this.arrays.put("mappings", mappings);

        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private void populateYarn() {
        Collection<String> gameYarn = new LinkedHashSet<>();
        JsonArray yarn = new JsonArray();
        Map<String, JsonArray> yarnVersions = new HashMap<>();

        try {
            for (MavenRepository.ArtifactMetadata.Artifact artifact : this.maven.getMetadata(this.group, "yarn")) {
                JsonObject object = new JsonObject();

                String gameVersion = stripInfo(artifact.version);
                object.addProperty("gameVersion", gameVersion);
                object.addProperty("separator", artifact.version.contains("+build.") ? "+build." : ".");
                object.addProperty("build", Integer.parseInt(artifact.version.substring(artifact.version.lastIndexOf(".") + 1)));
                object.addProperty("maven", artifact.mavenId());
                object.addProperty("version", artifact.version);

                yarn.add(object);
                gameYarn.add(gameVersion);
                yarnVersions.computeIfAbsent(gameVersion, v -> new JsonArray()).add(object);
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        JsonArray array = new JsonArray();
        gameYarn.forEach(array::add);

        this.arrays.put("mappings", yarn);
        this.upload("v3/versions/game/yarn", this.gson.toJson(array));
        this.upload("v3/versions/yarn", this.gson.toJson(yarn));

        for (Map.Entry<String, JsonArray> entry : yarnVersions.entrySet()) {
            this.upload("v3/versions/yarn/" + entry.getKey(), this.gson.toJson(entry.getValue()));
        }
    }

    private void populateInstaller() {
        JsonArray installer = new JsonArray();

        try {
            for (MavenRepository.ArtifactMetadata.Artifact artifact : this.maven.getMetadata(this.group, System.getenv("META_INSTALLER"))) {
                JsonObject object = new JsonObject();

                object.addProperty("url", artifact.url());
                object.addProperty("maven", artifact.mavenId());
                object.addProperty("version", artifact.version);

                installer.add(object);
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        this.arrays.put("installer", installer);
        this.upload("v3/versions/installer", this.gson.toJson(installer));
    }

    private Runnable populateLoader(ExecutorService executor) {
        return () -> {
            CompletableFuture.runAsync(this::populateLoader, executor).join();

            @SuppressWarnings("unchecked")
            CompletableFuture<Void>[] futures = (CompletableFuture<Void>[]) Array.newInstance(CompletableFuture.class, this.loaderVersions.size());
            int i = 0;

            for (MavenRepository.ArtifactMetadata.Artifact artifact : this.loaderVersions) {
                futures[i++] = CompletableFuture.runAsync(() -> {
                    try {
                        URL url = new URL(artifact.url().replace(".jar", ".json"));
                        JsonElement launcherMeta = JsonParser.parseReader(new InputStreamReader(url.openStream()));
                        this.launcherMetaData.put(artifact.mavenId(), launcherMeta);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                }, executor);
            }

            CompletableFuture.allOf(futures).join();
        };
    }

    private void populateLoader() {
        JsonArray loader = new JsonArray();

        try {
            for (MavenRepository.ArtifactMetadata.Artifact artifact : this.maven.getMetadata(this.group, System.getenv("META_LOADER"))) {
                JsonObject object = new JsonObject();

                object.addProperty("separator", artifact.version.contains("+build.") ? "+build." : ".");
                object.addProperty("build", Integer.parseInt(artifact.version.substring(artifact.version.lastIndexOf(".") + 1)));
                object.addProperty("maven", artifact.mavenId());

                String version = artifact.version.contains("+build.")
                        ? artifact.version.substring(0, artifact.version.lastIndexOf('+'))
                        : artifact.version;

                object.addProperty("version", version);

                loader.add(object);

                this.loaderVersions.add(artifact);
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        this.arrays.put("loader", loader);
        this.upload("v3/versions/loader", this.gson.toJson(loader));
    }

    private CompletableFuture<Void> populateIntermediaryAndGame(Executor executor) {
        Collection<String> gameIntermediary = new LinkedHashSet<>();
        JsonArray intermediary = new JsonArray();
        Map<String, JsonArray> intermediaryVersions = new HashMap<>();

        try {
            MavenRepository.ArtifactMetadata intermediaries = this.maven.getMetadata(this.group, "intermediary");

            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                JsonArray meta = MinecraftMeta.get(intermediaries, gson);
                this.arrays.put("game", meta);
                this.upload("v3/versions/game", this.gson.toJson(meta));
            }, executor);

            for (MavenRepository.ArtifactMetadata.Artifact artifact : intermediaries) {
                JsonObject object = new JsonObject();

                object.addProperty("maven", artifact.mavenId());
                object.addProperty("version", artifact.version);

                intermediary.add(object);
                gameIntermediary.add(artifact.version);
                this.gameIntermediaries.putIfAbsent(artifact.version, object);
                intermediaryVersions.computeIfAbsent(artifact.version, v -> new JsonArray()).add(object);
            }

            JsonArray array = new JsonArray();
            gameIntermediary.forEach(array::add);

            this.arrays.put("intermediary", intermediary);
            this.upload("v3/versions/game/intermediary", this.gson.toJson(array));
            this.upload("v3/versions/intermediary", this.gson.toJson(intermediary));

            for (Map.Entry<String, JsonArray> entry : intermediaryVersions.entrySet()) {
                this.upload("v3/versions/intermediary/" + entry.getKey(), this.gson.toJson(entry.getValue()));
            }

            return future;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private void populateLoaderVersions() {
        for (JsonElement gameVersionElement : this.arrays.get("game")) {
            String gameVersion = gameVersionElement.getAsJsonObject().get("version").getAsString();
            JsonArray gameLoaderVersion = new JsonArray();

            for (JsonElement loaderVersionElement : this.arrays.get("loader")) {
                String loaderVersion = loaderVersionElement.getAsJsonObject().get("version").getAsString();

                JsonObject object = new JsonObject();

                object.add("loader", loaderVersionElement);
                object.add("intermediary", this.gameIntermediaries.get(gameVersion));
                object.add("launcherMeta", this.launcherMetaData.get(
                        loaderVersionElement.getAsJsonObject().get("maven").getAsString()
                ));

                gameLoaderVersion.add(object);

                this.upload(String.format("v3/versions/loader/%s/%s", gameVersion, loaderVersion), this.gson.toJson(object));
            }

            this.upload(String.format("v3/versions/loader/%s", gameVersion), this.gson.toJson(gameLoaderVersion));
        }
    }

    private void populateProfiles() {
        String currentTime = ISO_8601.format(new Date());

        for (Side side : Side.values()) {
            for (JsonElement gameVersionElement : this.arrays.get("game")) {
                String gameVersion = gameVersionElement.getAsJsonObject().get("version").getAsString();

                for (JsonElement loaderVersionElement : this.arrays.get("loader")) {
                    String loaderVersion = loaderVersionElement.getAsJsonObject().get("version").getAsString();

                    JsonObject intermediary = this.gameIntermediaries.get(gameVersion);

                    JsonObject launcherMeta = this.launcherMetaData.get(
                            loaderVersionElement.getAsJsonObject().get("maven").getAsString()
                    ).getAsJsonObject();

                    JsonArray libraries = new JsonArray();

                    libraries.addAll(launcherMeta.get("libraries").getAsJsonObject().get("common").getAsJsonArray());
                    libraries.add(getLibrary(intermediary.get("maven").getAsString(), this.maven.url));
                    libraries.add(getLibrary(loaderVersionElement.getAsJsonObject().get("maven").getAsString(), this.maven.url));

                    if (launcherMeta.get("libraries").getAsJsonObject().has(side.side)) {
                        libraries.addAll(launcherMeta.get("libraries").getAsJsonObject().get(side.side).getAsJsonArray());
                    }

                    JsonObject arguments = new JsonObject();
                    arguments.add("game", new JsonArray());

                    JsonObject object = new JsonObject();

                    object.addProperty("id", String.format("quilt-loader-%s-%s", loaderVersion, gameVersion));
                    object.addProperty("inheritsFrom", gameVersion);
                    object.addProperty("releaseTime", currentTime);
                    object.addProperty("time", currentTime);
                    object.addProperty("type", "release");

                    if (launcherMeta.get("mainClass").isJsonObject()) {
                        object.addProperty("mainClass", launcherMeta.get("mainClass").getAsJsonObject().get(side.side).getAsString());
                    }

                    object.add("arguments", arguments);
                    object.add("libraries", libraries);

                    this.upload(String.format("v3/versions/loader/%s/%s/%s/json", gameVersion, loaderVersion, side.type), this.gson.toJson(object));
                }
            }
        }
    }

    private void upload(String fileName, String fileContents) {
        this.upload(fileName, fileContents.getBytes(StandardCharsets.UTF_8), "application/json");
    }

    private void upload(String fileName, byte[] fileContents, String contentType) {
        this.files.put(fileName, Pair.of(fileContents, contentType));
    }

    private void upload() {
        ExecutorService executor = Executors.newFixedThreadPool(50);
        S3Client s3 = S3Client.create();
        String bucket = System.getenv("META_BUCKET");

        @SuppressWarnings("unchecked")
        CompletableFuture<Void>[] futures = (CompletableFuture<Void>[]) Array.newInstance(CompletableFuture.class, this.files.size());
        int i = 0;

        for (String file : this.files.keySet()) {
            futures[i++] = CompletableFuture.runAsync(() -> {
                Pair<byte[], String> pair = this.files.get(file);
                byte[] contentBytes = pair.left();

                PutObjectRequest.Builder builder = PutObjectRequest.builder();
                builder.bucket(bucket);
                builder.key(file);
                builder.contentType(pair.right());
                builder.contentLength((long) contentBytes.length);

                try {
                    s3.putObject(builder.build(), RequestBody.fromBytes(contentBytes));
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }, executor);
        }

        CompletableFuture.allOf(futures).join();
    }

    private static String stripInfo(String version) {
        if (version.contains("+build.")) {
            return version.substring(0, version.lastIndexOf('+'));
        } else {
            //TODO legacy remove when no longer needed
            char verSep = version.contains("-") ? '-' : '.';
            return version.substring(0, version.lastIndexOf(verSep));
        }
    }

    private static <T> JsonArray toJson(Iterable<T> items, Function<T, JsonElement> function) {
        JsonArray array = new JsonArray();

        for (T t : items) {
            JsonElement element = function.apply(t);

            if (element != null && !array.contains(element)) {
                array.add(element);
            }
        }

        return array;
    }

    private static JsonObject getLibrary(String mavenPath, String url) {
        JsonObject jsonObject = new JsonObject();
        jsonObject.addProperty("name", mavenPath);
        jsonObject.addProperty("url", url);
        return jsonObject;
    }

    private enum Side {
        CLIENT("client", "profile"), SERVER("server", "server");

        final String side;
        final String type;

        Side(String side, String type) {
            this.side = side;
            this.type = type;
        }
    }
}
