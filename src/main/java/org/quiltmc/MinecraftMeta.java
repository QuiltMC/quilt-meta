package org.quiltmc;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import jdk.nashorn.api.scripting.URLReader;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.List;

public class MinecraftMeta {
    private static final URL MANIFEST;

    static {
        URL url = null;

        try {
            url = new URL("https://launchermeta.mojang.com/mc/game/version_manifest.json");
        } catch (MalformedURLException e) {
            e.printStackTrace();
        }

        MANIFEST = url;
    }

    private MinecraftMeta() {
    }

    @SuppressWarnings({"unused", "MismatchedQueryAndUpdateOfCollection"})
    private List<Version> versions;

    public static JsonArray get(MavenRepository.ArtifactMetadata intermediaries, Gson gson) {
        JsonArray versions = new JsonArray();

        MinecraftMeta meta = gson.fromJson(new URLReader(MANIFEST), MinecraftMeta.class);

        for (Version version : meta.versions) {
            if (intermediaries.contains(version.id)) {
                JsonObject object = new JsonObject();

                object.addProperty("version", version.id);
                object.addProperty("stable", version.type.equals("release"));

                versions.add(object);
            }
        }

        return versions;
    }

    private static class Version {
        String id;
        String type;
    }
}
