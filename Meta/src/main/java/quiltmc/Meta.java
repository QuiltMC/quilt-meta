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
    public APIGatewayProxyResponseEvent handleRequest(final APIGatewayProxyRequestEvent input, final Context context) {
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("X-Custom-Header", "application/json");

        APIGatewayProxyResponseEvent response = new APIGatewayProxyResponseEvent()
                .withHeaders(headers);

        try {
            Gson gson = new GsonBuilder().setPrettyPrinting().create();
            MavenRepository maven = new MavenRepository(System.getenv("META_MAVEN_URL"));
            String group = System.getenv("META_GROUP");
            ExecutorService executor = Executors.newCachedThreadPool();
            Mutable<Exception> exception = new Mutable<>();
            Map<String, String> files = new ConcurrentHashMap<>();
            Map<String, JsonArray> arrays = new ConcurrentHashMap<>();
            Map<String, JsonElement> launcherMetaData = new ConcurrentHashMap<>();

            executor.execute(() -> {
                try {
                    arrays.put("mappings", toJson(maven.getMetadata(group, "yarn"),
                            version -> new JsonPrimitive(stripInfo(version.version))));
                } catch (IOException e) {
                    exception.set(e);
                }
            });

            executor.execute(() -> {
                Collection<String> gameYarn = new LinkedHashSet<>();
                JsonArray yarn = new JsonArray();
                Map<String, JsonArray> yarnVersions = new HashMap<>();

                try {
                    for (MavenRepository.ArtifactMetadata.Artifact artifact : maven.getMetadata(group, "yarn")) {
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
                    exception.set(e);
                }

                JsonArray array = new JsonArray();
                gameYarn.forEach(array::add);

                arrays.put("mappings", yarn);
                files.put("v3/versions/game/yarn", gson.toJson(array));
                files.put("v3/versions/yarn", gson.toJson(yarn));

                for (Map.Entry<String, JsonArray> entry : yarnVersions.entrySet()) {
                    files.put("v3/versions/yarn/" + entry.getKey(), gson.toJson(entry.getValue()));
                }
            });

            Map<String, JsonObject> gameIntermediaries = new ConcurrentHashMap<>();

            executor.execute(() -> {
                Collection<String> gameIntermediary = new LinkedHashSet<>();
                JsonArray intermediary = new JsonArray();
                Map<String, JsonArray> intermediaryVersions = new HashMap<>();

                try {
                    MavenRepository.ArtifactMetadata intermediaries = maven.getMetadata(group, "intermediary");

                    JsonArray meta = MinecraftMeta.get(intermediaries, gson);
                    arrays.put("game", meta);
                    files.put("v3/versions/game", gson.toJson(meta));

                    for (MavenRepository.ArtifactMetadata.Artifact artifact : intermediaries) {
                        JsonObject object = new JsonObject();

                        object.addProperty("maven", artifact.mavenId());
                        object.addProperty("version", artifact.version);

                        intermediary.add(object);
                        gameIntermediary.add(artifact.version);
                        gameIntermediaries.putIfAbsent(artifact.version, object);
                        intermediaryVersions.computeIfAbsent(artifact.version, v -> new JsonArray()).add(object);
                    }
                } catch (IOException e) {
                    exception.set(e);
                }

                JsonArray array = new JsonArray();
                gameIntermediary.forEach(array::add);

                arrays.put("intermediary", intermediary);
                files.put("v3/versions/game/intermediary", gson.toJson(array));
                files.put("v3/versions/intermediary", gson.toJson(intermediary));

                for (Map.Entry<String, JsonArray> entry : intermediaryVersions.entrySet()) {
                    files.put("v3/versions/intermediary/" + entry.getKey(), gson.toJson(entry.getValue()));
                }
            });

            Collection<MavenRepository.ArtifactMetadata.Artifact> loaderVersions = new ConcurrentLinkedDeque<>();

            executor.execute(() -> {
                JsonArray loader = new JsonArray();

                try {
                    for (MavenRepository.ArtifactMetadata.Artifact artifact : maven.getMetadata(group, System.getenv("META_LOADER"))) {
                        JsonObject object = new JsonObject();

                        object.addProperty("separator", artifact.version.contains("+build.") ? "+build." : ".");
                        object.addProperty("build", Integer.parseInt(artifact.version.substring(artifact.version.lastIndexOf(".") + 1)));
                        object.addProperty("maven", artifact.mavenId());

                        String version = artifact.version.contains("+build.")
                                ? artifact.version.substring(0, artifact.version.lastIndexOf('+'))
                                : artifact.version;

                        object.addProperty("version", version);

                        loader.add(object);

                        loaderVersions.add(artifact);
                    }
                } catch (IOException e) {
                    exception.set(e);
                }

                arrays.put("loader", loader);
                files.put("v3/versions/loader", gson.toJson(loader));
            });

            executor.execute(() -> {
                JsonArray installer = new JsonArray();

                try {
                    for (MavenRepository.ArtifactMetadata.Artifact artifact : maven.getMetadata(group, System.getenv("META_INSTALLER"))) {
                        JsonObject object = new JsonObject();

                        object.addProperty("url", artifact.url());
                        object.addProperty("maven", artifact.mavenId());
                        object.addProperty("version", artifact.version);

                        installer.add(object);
                    }
                } catch (IOException e) {
                    exception.set(e);
                }

                arrays.put("installer", installer);
                files.put("v3/versions/installer", gson.toJson(installer));
            });

            wait(executor, exception);
            executor = Executors.newCachedThreadPool();

            for (MavenRepository.ArtifactMetadata.Artifact artifact : loaderVersions) {
                executor.execute(() -> {
                    try {
                        URL url = new URL(artifact.url().replace(".jar", ".json"));
                        JsonElement launcherMeta = JsonParser.parseReader(new InputStreamReader(url.openStream()));
                        launcherMetaData.put(artifact.mavenId(), launcherMeta);
                    } catch (IOException e) {
                        exception.set(e);
                    }
                });
            }

            wait(executor, exception);

            System.out.println("Building loader stuff");

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

            JsonObject versions = new JsonObject();

            versions.add("game", arrays.get("game"));
            versions.add("mappings", arrays.get("mappings"));
            versions.add("intermediary", arrays.get("intermediary"));
            versions.add("loader", arrays.get("loader"));
            versions.add("installer", arrays.get("installer"));
//
            files.put("v3/versions", gson.toJson(versions));
            files.put("v3/versions/game", gson.toJson(arrays.get("game")));

            System.out.printf("Updating %d files", files.size());

            Mutable<Exception> exception1 = update(files);

            if (exception1.get() != null) {
                throw exception1.get();
            }

            System.out.println("Done updating files");

            return response
                    .withStatusCode(200)
                    .withBody(gson.toJson(versions));
        } catch (Exception e) {
            return response
                    .withBody(String.format("{\"message\": \"%s\"}", e.getMessage()))
                    .withStatusCode(500);
        }
    }

    private static void wait(ExecutorService executor, Mutable<Exception> exception) throws Exception {
        executor.shutdown();

        System.out.println("Waiting for threads...");

        if (!executor.awaitTermination(24L, TimeUnit.HOURS)) {
            throw new InterruptedException();
        }

        if (exception.get() != null) {
            throw exception.get();
        }

        System.out.println("Threads done");
    }

    private Mutable<Exception> update(Map<String, String> files) {
        ExecutorService executor = Executors.newFixedThreadPool(50);
        S3Client s3 = S3Client.create();
        String bucket = System.getenv("META_BUCKET");
        Mutable<Exception> exception = new Mutable<>();

        int i = 0;
        for (String file : files.keySet()) {
            executor.execute(() -> {
                byte[] contentBytes = files.get(file).getBytes(StandardCharsets.UTF_8);

                PutObjectRequest.Builder builder = PutObjectRequest.builder();
                builder.bucket(bucket);
                builder.key(file);
                builder.contentType("application/json");
                builder.contentLength((long) contentBytes.length);

                try {
                    s3.putObject(builder.build(), RequestBody.fromBytes(contentBytes));
                } catch (Exception e) {
                    exception.set(e);
                }
            });
        }

        try {
            executor.shutdown();

            if (!executor.awaitTermination(24L, TimeUnit.HOURS)) {
                throw new InterruptedException();
            }

            return exception;
        } catch (InterruptedException e) {
            exception.set(e);
            return exception;
        }
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
}
