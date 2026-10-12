/*
 * Copyright 2026 HushGram contributors. https://github.com/SysAdminDoc/HushGram
 * SPDX-License-Identifier: GPL-3.0-only
 */
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Properties;

/** Runs from the JDK before either launcher loads any wrapper JAR code. */
public final class VerifyGradleWrapper {
    // Independently reviewed at https://gradle.org/release-checksums/ on 2026-10-12. 9.8.1 fixes
    // GHSA-xwqc-3h47-hg64, GHSA-mvvg-497x-hmj8 and GHSA-j5m7-59rp-24f5 (all HIGH, 9.0.0 to 9.8.0).
    private static final String VERSION = "9.8.1";
    private static final String JAR_SHA256 = "3b8a25775a69158b5ad2b1d17a80a88dc7b40a352a73eb27d06c612a7ce68e98";
    private static final String ZIP_SHA256 = "dce76f55f8e251a3a1f130eb120f30b3d271de2b76c9b0729d316b5a1b6dc01f";

    public static void main(String[] args) {
        try {
            if (args.length != 1) throw new IllegalArgumentException("Expected the checkout directory.");
            Path wrapper = Path.of(args[0]).resolve("gradle/wrapper");
            Properties properties = new Properties();
            try (InputStream input = Files.newInputStream(wrapper.resolve("gradle-wrapper.properties"))) {
                properties.load(input);
            } catch (Exception failure) {
                throw new IllegalStateException("Gradle wrapper properties are unreadable.", failure);
            }
            String reviewedUrl = "https://services.gradle.org/distributions/gradle-" + VERSION + "-bin.zip";
            if (!reviewedUrl.equals(properties.getProperty("distributionUrl"))) {
                throw new IllegalStateException("The wrapper distribution is not the reviewed Gradle " + VERSION + " binary URL.");
            }
            if (!ZIP_SHA256.equals(properties.getProperty("distributionSha256Sum"))) {
                throw new IllegalStateException("Gradle distribution ZIP SHA-256 does not match the reviewed publisher checksum.");
            }
            Path jar = wrapper.resolve("gradle-wrapper.jar");
            if (!Files.isRegularFile(jar)) throw new IllegalStateException("Gradle wrapper JAR is missing.");
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(jar)) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
            }
            if (!JAR_SHA256.equals(HexFormat.of().formatHex(digest.digest()))) {
                throw new IllegalStateException("Gradle wrapper JAR SHA-256 does not match the reviewed Gradle " + VERSION + " publisher checksum.");
            }
        } catch (Exception failure) {
            System.err.println("[wrapper] Refusing to execute: " + failure.getMessage());
            System.err.println("Review the version and official checksums at https://gradle.org/release-checksums/ before changing this gate.");
            System.exit(1);
        }
    }
}
