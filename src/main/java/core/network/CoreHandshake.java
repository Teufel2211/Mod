package core.network;

import net.minecraft.util.Identifier;

public final class CoreHandshake {
    public static final Identifier CHANNEL = Identifier.of("core", "handshake");
    public static final int PROTOCOL = 1;

    private CoreHandshake() {}
}

