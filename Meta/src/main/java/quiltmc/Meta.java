package quiltmc;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.google.gson.*;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.Array;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;

/**
 * Handler for requests to Lambda function.
 */
@SuppressWarnings("unused")
public class Meta implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private final MavenRepository maven = new MavenRepository(System.getenv("META_MAVEN_URL"));
    private final String group = System.getenv("META_GROUP");
    private final Map<String, String> files = new ConcurrentHashMap<>();
    private final Map<String, JsonArray> arrays = new ConcurrentHashMap<>();
    private final Map<String, JsonElement> launcherMetaData = new ConcurrentHashMap<>();
    private final Map<String, JsonObject> gameIntermediaries = new ConcurrentHashMap<>();
    private final Deque<MavenRepository.ArtifactMetadata.Artifact> loaderVersions = new ConcurrentLinkedDeque<>();

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

            JsonObject versions = new JsonObject();

            versions.add("game", this.arrays.get("game"));
            versions.add("mappings", this.arrays.get("mappings"));
            versions.add("intermediary", this.arrays.get("intermediary"));
            versions.add("loader", this.arrays.get("loader"));
            versions.add("installer", this.arrays.get("installer"));

            files.put("v3/versions", this.gson.toJson(versions));
            files.put("v3/versions/game", this.gson.toJson(this.arrays.get("game")));

            System.out.printf("Updating %d files", this.files.size());

            this.update();

            System.out.println("Done updating files");

            return response
                    .withStatusCode(200)
                    .withBody(this.gson.toJson(versions));
        } catch (Exception e) {
            return response
                    .withBody(String.format("{\"message\": \"%s\"}", e.toString()))
                    .withStatusCode(500);
        }
    }

    private void update() {
        ExecutorService executor = Executors.newFixedThreadPool(50);
        S3Client s3 = S3Client.create();
        String bucket = System.getenv("META_BUCKET");

        @SuppressWarnings("unchecked")
        CompletableFuture<Void>[] futures = (CompletableFuture<Void>[]) Array.newInstance(CompletableFuture.class, this.files.size());
        int i = 0;

        for (String file : this.files.keySet()) {
            futures[i++] = CompletableFuture.runAsync(() -> {
                byte[] contentBytes = this.files.get(file).getBytes(StandardCharsets.UTF_8);

                PutObjectRequest.Builder builder = PutObjectRequest.builder();
                builder.bucket(bucket);
                builder.key(file);
                builder.contentType("application/json");
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

    private void populateLoaderVersions() {
        for (JsonElement gameVersionElement : arrays.get("game")) {
            String gameVersion = gameVersionElement.getAsJsonObject().get("version").getAsString();
            JsonArray gameLoaderVersion = new JsonArray();

            for (JsonElement loaderVersionElement : arrays.get("loader")) {
                String loaderVersion = loaderVersionElement.getAsJsonObject().get("version").getAsString();

                JsonObject object = new JsonObject();

                object.add("loader", loaderVersionElement);
                object.add("intermediary", gameIntermediaries.get(gameVersion));
                object.add("launcherMeta", launcherMetaData.get(
                        loaderVersionElement.getAsJsonObject().get("maven").getAsString()
                ));

                gameLoaderVersion.add(object);

                files.put(String.format("v3/versions/loader/%s/%s", gameVersion, loaderVersion), gson.toJson(object));
            }

            files.put(String.format("v3/versions/loader/%s", gameVersion), gson.toJson(gameLoaderVersion));
        }
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
        this.files.put("v3/versions/game/yarn", this.gson.toJson(array));
        this.files.put("v3/versions/yarn", this.gson.toJson(yarn));

        for (Map.Entry<String, JsonArray> entry : yarnVersions.entrySet()) {
            this.files.put("v3/versions/yarn/" + entry.getKey(), this.gson.toJson(entry.getValue()));
        }

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
                this.files.put("v3/versions/game", this.gson.toJson(meta));
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
            this.files.put("v3/versions/game/intermediary", this.gson.toJson(array));
            this.files.put("v3/versions/intermediary", this.gson.toJson(intermediary));

            for (Map.Entry<String, JsonArray> entry : intermediaryVersions.entrySet()) {
                this.files.put("v3/versions/intermediary/" + entry.getKey(), this.gson.toJson(entry.getValue()));
            }

            return future;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
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
        this.files.put("v3/versions/loader", this.gson.toJson(loader));
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
        this.files.put("v3/versions/installer", this.gson.toJson(installer));
    }
}
