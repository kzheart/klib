package me.kzheart.klib.remote;

import java.io.IOException;

interface RpkTransport {
    String settings() throws IOException;
    String sendBatch(byte[] json) throws IOException;

    default String queueIdentity() {
        return getClass().getName();
    }
}
