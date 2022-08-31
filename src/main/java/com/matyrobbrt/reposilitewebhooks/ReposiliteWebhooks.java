package com.matyrobbrt.reposilitewebhooks;

import com.reposilite.maven.MavenFacade;
import com.reposilite.maven.api.DeployEvent;
import com.reposilite.plugin.api.Facade;
import com.reposilite.plugin.api.Plugin;
import com.reposilite.plugin.api.ReposilitePlugin;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;

@Plugin(name = "webhooks")
public class ReposiliteWebhooks extends ReposilitePlugin {
    @Override
    public @Nullable Facade initialize() {
        final var facade = new WebhookFacade(extensions().facade(MavenFacade.class));
        extensions().registerEvent(DeployEvent.class, event -> {
            try {
                facade.sendWebhook(event, facade.getWebhookTargets(
                        event.getRepository().getName(),
                        event.getGav()
                ));
            } catch (IOException e) {
                getLogger().error("Exception trying to send webhooks: {}", e);
            }
        });
        return facade;
    }
}
