package com.ezbackup.client;

import net.fabricmc.api.ClientModInitializer;

/**
 * Client entry point (the "client" entrypoint of fabric.mod.json). Fabric only loads it on a game client, so a dedicated
 * server never touches the GUI classes in this package.
 */
public final class EzBackupClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        ClientEvents.register();
    }
}
