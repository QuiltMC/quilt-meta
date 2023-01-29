# :warning: Archival Notice

As of January 2023, we've switched away from this AWS Lambda + S3 based setup to GitHub Actions + B2, for cost reduction reasons.

**The new code can be found in [QuiltMC/update-quilt-meta](https://github.com/QuiltMC/update-quilt-meta).**

The content of the Readme has been kept underneath for archival purposes.

# quilt-meta

Quilt Meta ia a json HTTP api that can be used to query meta data about Quilt projects.

It can be used by tools or launchers that wish to query version infomation about Quilt.

Hosted at [https://meta.quiltmc.org/](https://meta.quiltmc.org/)

## Endpoints

The versions are in order, the newest versions appear first

`game_version` and `loader_version` should be url encoded to allow for special characters. For example `1.14 Pre-Release 5` becomes `1.14%20Pre-Release%205`

# V3

## /v3/versions

Full database, includes all the data, warning large json

## /v3/versions/game

Lists all of the supported game versions

```json
[
  {
    "version": "1.14",
    "stable": true
  },
  {
    "version": "1.14 Pre-Release 5",
    "stable": false
  }
]
```

## /v3/versions/game/quilt-mappings

Lists all of the compatible game versions for Quilt Mappings

```json
[
  "1.14.3-pre2",
  "1.14.3-pre1"
]
```

## /v3/versions/game/hashed

Lists all of the compatible game versions for Hashed Mojmap

```json
[
  "1.14.3-pre3",
  "1.14.3-pre2"
]
```

## /v3/versions/hashed

Lists all of the Hashed Mojmap versions

```json
[
  {
    "maven": "org.quiltmc:hashed:1.18",
    "version": "1.18"
  },
  {
    "maven": "org.quiltmc:hashed:1.18",
    "version": "1.18"
  }
]
```

## /v3/versions/hashed/:game_version

Lists all of the Hashed Mojmap for the provided game version


```json
[
  {
    "maven": "org.quiltmc:hashed:1.18",
    "version": "1.18"
  }
]
```

## /v3/versions/quilt-mappings

Lists all of the Quilt Mappings versions

```json
[
  {
    "gameVersion": "1.18-pre7",
    "separator": "+build.",
    "build": 3,
    "maven": "org.quiltmc:quilt-mappings:1.18-pre7+build.3",
    "version": "1.18-pre7+build.3",
    "hashed": "1.18-pre7"
  },
  {
    "gameVersion": "1.18-pre7",
    "separator": "+build.",
    "build": 2,
    "maven": "org.quiltmc:quilt-mappings:1.18-pre7+build.2",
    "version": "1.18-pre7+build.2",
    "hashed": "1.18-pre7"
  }
]
```

## /v3/versions/quilt-mappings/:game_version

Lists all of the Quilt Mappings versions for the provided game version


```json
[
  {
    "gameVersion": "1.18-pre7",
    "separator": "+build.",
    "build": 3,
    "maven": "org.quiltmc:quilt-mappings:1.18-pre7+build.3",
    "version": "1.18-pre7+build.3",
    "hashed": "1.18-pre7"
  },
  {
    "gameVersion": "1.18-pre7",
    "separator": "+build.",
    "build": 2,
    "maven": "org.quiltmc:quilt-mappings:1.18-pre7+build.2",
    "version": "1.18-pre7+build.2",
    "hashed": "1.18-pre7"
  }
]
```

## /v3/versions/loader

Lists all of the loader versions


```json
[
  {
    "separator": "+build.",
    "build": 132,
    "maven": "org.quiltmc:quilt-loader:0.4.2+build.132",
    "version": "0.4.2+build.132"
  },
  {
    "separator": "+build.",
    "build": 131,
    "maven": "org.quiltmc:quilt-loader:0.4.2+build.131",
    "version": "0.4.2+build.131"
  }
]
```

### /v3/versions/loader/:game_version

This returns a list of all the compatible loader versions for a given version of the game, along with the best version of Hashed Mojmap to use for that version

```json
[
  {
    "loader": {
      "separator": "+build.",
      "build": 155,
      "maven": "org.quiltmc:quilt-loader:0.4.8+build.155",
      "version": "0.4.8+build.155"
    },
    "hashed": {
      "maven": "org.quiltmc:hashed:1.18",
      "version": "1.18"
    }
  },
  {
    "loader": {
      "separator": "+build.",
      "build": 154,
      "maven": "org.quiltmc:quilt-loader:0.4.8+build.154",
      "version": "0.4.8+build.154"
    },
    "hashed": {
      "maven": "org.quiltmc:hashed:1.18",
      "version": "1.18"
    }
  }
]
```


## /v3/versions/loader/:game_version/:loader_version

This returns the best Hashed Mojmap for the supplied minecraft version, as well as the details for the supplied loader version. This should be used if you want to install a specific version of loader along with some Hashed Mojmap for a specific game version.

`launcherMeta` can be used to get the library's required by quilt-loader as well as the main class for each side.

```json
{
  "loader": {
    "separator": "+build.",
    "build": 155,
    "maven": "org.quiltmc:quilt-loader:0.4.8+build.155",
    "version": "0.4.8+build.155"
  },
  "hashed": {
    "maven": "org.quiltmc:hashed:1.18",
    "version": "1.18"
  },
  "launcherMeta": {
    "version": 1,
    "libraries": {
      "client": [
        
      ],
      "common": [
        {
          "name": "org.quiltmc:tiny-mappings-parser:0.1.1.8",
          "url": "https://maven.quiltmc.org/repository/release/"
        },
        {
          "name": "org.quiltmc:sponge-mixin:0.7.11.36",
          "url": "https://maven.quiltmc.org/repository/release/"
        },
        {
          "name": "org.quiltmc:tiny-remapper:0.1.0.33",
          "url": "https://maven.quiltmc.org/repository/release/"
        },
        {
          "name": "org.quiltmc:quilt-loader-sat4j:2.3.5.4",
          "url": "https://maven.quiltmc.org/repository/release/"
        },
        {
          "name": "com.google.jimfs:jimfs:1.1",
          "url": "https://maven.quiltmc.org/repository/release/"
        },
        {
          "name": "org.ow2.asm:asm:7.1",
          "url": "https://maven.quiltmc.org/repository/release/"
        },
        {
          "name": "org.ow2.asm:asm-analysis:7.1",
          "url": "https://maven.quiltmc.org/repository/release/"
        },
        {
          "name": "org.ow2.asm:asm-commons:7.1",
          "url": "https://maven.quiltmc.org/repository/release/"
        },
        {
          "name": "org.ow2.asm:asm-tree:7.1",
          "url": "https://maven.quiltmc.org/repository/release/"
        },
        {
          "name": "org.ow2.asm:asm-util:7.1",
          "url": "https://maven.quiltmc.org/repository/release/"
        }
      ],
      "server": [
        {
          "_comment": "jimfs in quilt-server-launch requires guava on the system classloader",
          "name": "com.google.guava:guava:21.0",
          "url": "https://maven.quiltmc.org/repository/release/"
        }
      ]
    },
    "mainClass": {
      "client": "org.quiltmc.loader.launch.knot.KnotClient",
      "server": "org.quiltmc.loader.launch.knot.KnotServer"
    }
  }
}
```

## /v3/versions/loader/:game_version/:loader_version/profile/json

Returns the json file that should be used in the standard minecraft launcher

## /v3/versions/loader/:game_version/:loader_version/profile/zip

Downloads a zip file with the launcher's profile json and the dummy jar to be extracted into .minecraft/versions

## /v3/versions/loader/:game_version/:loader_version/server/json

Returns the json file in format of the launcher json, but with the server's main class.
