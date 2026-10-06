package com.local.dasherfilter;

import java.nio.file.*;
import java.io.*;

/** Uses the actual app HTTP implementation. No alternative curl/Python download path. */
public final class UpdateChannelProbe {
    public static void main(String[] args) throws Exception {
        if (args.length == 8 && args[0].equals("metadata")) {
            UpdatePolicy.validate(args[1], Long.parseLong(args[2]), args[3], args[4], Long.parseLong(args[5]), args[6]);
            if (!UpdatePolicy.validVersionName(args[7])) throw new IllegalArgumentException("Invalid version name");
            System.out.println("APP_METADATA_POLICY_PASS"); return;
        }
        if (args.length != 4 || !args[0].equals("download")) throw new IllegalArgumentException("Usage: download URL output limit | metadata package code URL hash size encoding version");
        Path output = Paths.get(args[2]);
        try (OutputStream out = Files.newOutputStream(output)) { UpdateTransport.download(args[1], out, Long.parseLong(args[3])); }
        System.out.println("APP_HTTP_TRANSPORT_PASS bytes=" + Files.size(output));
    }
}
