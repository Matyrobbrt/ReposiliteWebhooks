package com.matyrobbrt.reposilitewebhooks;

import club.minnced.discord.webhook.WebhookClient;
import club.minnced.discord.webhook.WebhookClientBuilder;
import club.minnced.discord.webhook.send.WebhookEmbed;
import club.minnced.discord.webhook.send.WebhookEmbedBuilder;
import club.minnced.discord.webhook.send.WebhookMessageBuilder;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.reposilite.maven.MavenFacade;
import com.reposilite.maven.api.DeployEvent;
import com.reposilite.maven.api.VersionLookupRequest;
import com.reposilite.plugin.api.Facade;
import com.reposilite.storage.api.Location;
import okhttp3.OkHttpClient;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

@SuppressWarnings("ClassCanBeRecord")
public class WebhookFacade implements Facade {
    private final MavenFacade maven;
    public WebhookFacade(MavenFacade maven) {
        this.maven = maven;
    }

    public static final Gson GSON = new Gson();
    public static final Path CONFIG_PATH = Path.of("webhooks.json");

    private static final OkHttpClient CLIENT = new OkHttpClient();
    private static final ScheduledExecutorService EXECUTOR = Executors.newScheduledThreadPool(2, r -> {
        final var thread = new Thread(r, "WebhookExecutor");
        thread.setDaemon(true);
        return thread;
    });
    private static final Map<String, WebhookClient> CLIENTS = new ConcurrentHashMap<>();
    private static final String ICON_URL = "https://github.com/reposilite-playground.png";

    static {
        //noinspection RedundantOperationOnEmptyContainer
        CLIENTS.forEach((a, b) -> b.close());
    }

    public void sendWebhook(DeployEvent event, List<String> targets) throws IOException {
        if (!event.getGav().toString().endsWith(".xml")) return;
        final var repoUrl = getRepoUrl();
        if (repoUrl == null) return;
        var gav = event.getGav().locationBeforeLast("/", "");
        final var latestVersion = maven.findLatestVersion(new VersionLookupRequest(
                null, event.getRepository(), gav, null
        )).orNull();
        if (latestVersion == null) return;
        gav = gav.resolve(latestVersion.getVersion());
        final var baseUrl = repoUrl + "#/" + event.getRepository().getName() + "/";
        final var finalGav = gav;
        targets.forEach(target -> {
            final var client = CLIENTS.computeIfAbsent(target, $ -> new WebhookClientBuilder(target).setHttpClient(CLIENT).setExecutorService(EXECUTOR).build());
            final var embed = new WebhookEmbedBuilder();
            final var location = Objects.requireNonNull(Utils.getData(event.getRepository().getName(), finalGav));

            embed.setAuthor(new WebhookEmbed.EmbedAuthor(
                    "Reposilite", ICON_URL, repoUrl
            ));
            embed.setColor(location.version.toLowerCase(Locale.ROOT).contains("snapshot") ? 0x080080 : 0x90ee90);
            embed.setTitle(new WebhookEmbed.EmbedTitle("A new artifact has been deployed!", baseUrl + finalGav));
            embed.setFooter(new WebhookEmbed.EmbedFooter("Deployment notifications", ICON_URL));
            embed.setTimestamp(Instant.now());

            final String by = event.getBy().substring(0, event.getBy().indexOf('@'));
            final String desc = "The version `%s` of `%s:%s` has been published by %s.\n".formatted(
                    location.version, location.group, location.name, by
            ) +
                    """
                            \n**Gradle Groovy**:
                            ```gradle
                            implementation '%s:%s:%s'
                            ```
                            """.formatted(
                            location.group, location.name, location.version
                    ) +
                    """
                            **Maven**:
                            ```xml
                            <dependency>
                                <groupId>%s</groupId>
                                <artifactId>%s</artifactId>
                                <version>%s</version>
                            </dependency>
                            ```
                            """.formatted(
                            location.group, location.name, location.version
                    );
            embed.setDescription(desc);

            client.send(new WebhookMessageBuilder()
                    .addEmbeds(embed.build())
                    .setUsername("Maven deployments")
                    .setAvatarUrl(ICON_URL)
                    .build());
        });
    }

    @Nullable
    public String getRepoUrl() throws IOException {
        if (!Files.exists(CONFIG_PATH)) return null;
        final var json = GSON.fromJson(Files.readString(CONFIG_PATH), JsonObject.class).get("repoUrl");
        if (json != null) {
            return json.getAsString();
        }
        return null;
    }

    public List<String> getWebhookTargets(String repoName, Location gav) throws IOException {
        if (!Files.exists(CONFIG_PATH)) return List.of();
        final var json = GSON.fromJson(Files.readString(CONFIG_PATH), JsonObject.class).getAsJsonObject(repoName);
        if (json != null) {
            final var location = Objects.requireNonNull(Utils.getData(repoName, gav));
            final var data = json.get(location.group + ":" + location.name);
            if (data instanceof JsonArray) {
                final var dataList = new ArrayList<String>();
                final var asArray = data.getAsJsonArray();
                asArray.forEach(it -> dataList.add(it.getAsString()));
                return dataList;
            }
            if (data != null) {
                return List.of(data.getAsString());
            }
        }
        return List.of();
    }
}
