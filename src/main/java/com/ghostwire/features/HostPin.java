package com.ghostwire.features;

/** Host header value for a service, including a port only when it is not the scheme default. */
public final class HostPin {

    private HostPin() {}

    public static String hostValue(String host, int port, boolean secure) {
        boolean standard = (secure && port == 443) || (!secure && port == 80);
        return standard ? host : host + ":" + port;
    }
}
