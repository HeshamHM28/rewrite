/*
 * Copyright 2023 the original author or authors.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * https://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.openrewrite.remote;

import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;
import java.util.function.Consumer;

public class LocalRemoteArtifactCache implements RemoteArtifactCache {
    private final Path cacheDir;
    static final ThreadLocal<MessageDigest> DIGEST = ThreadLocal.withInitial(() -> {
                try {
                    return MessageDigest.getInstance("SHA-256");
                } catch (NoSuchAlgorithmException e) {
                    // propagate as checked exception wrapped to match callers' expectations;
                    // the outer method handles this by rethrowing as RuntimeException.
                    throw new RuntimeException(e);
                }
            });
    static final char[] CHARS = {'0','1','2','3','4','5','6','7','8','9','a','b','c','d','e','f'};

    public LocalRemoteArtifactCache(Path cacheDir) {
        if (!cacheDir.toFile().exists() && !cacheDir.toFile().mkdirs()) {
            throw new IllegalStateException("Unable to find or create remote archive cache at " + cacheDir);
        }
        this.cacheDir = cacheDir;
    }

    @Override
    public @Nullable Path get(URI uri) {
        Path resolved = cacheDir.resolve(hashUri(uri));
        return Files.exists(resolved) ? resolved : null;
    }

    @Override
    public @Nullable Path put(URI uri, InputStream artifactInputStream, Consumer<Throwable> onError) {
        synchronized (this) {
            // create a temp file in the cache directory to avoid cross-filesystem moves and avoid
            // constructing UUID strings manually
            Path artifact = null;
            try {
                artifact = Files.createTempFile(cacheDir, "artifact-", ".tmp");
                try (InputStream is = artifactInputStream) {
                    Files.copy(is, artifact, StandardCopyOption.REPLACE_EXISTING);
                }

                Path cachedArtifact = cacheDir.resolve(hashUri(uri));
                if (!Files.exists(cachedArtifact)) {
                    Files.move(artifact, cachedArtifact, StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING);
                }
                return cachedArtifact;
            } catch (Exception e) {
                onError.accept(e);
                return null;
            } finally {
                if (artifact != null && Files.exists(artifact)) {
                    try {
                        Files.delete(artifact);
                    } catch (IOException ignored) {
                        // Suppress
                    }
                }
            }
        }
    }

    public static String hashUri(URI uri) {
        // hash the string using SHA-256
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(uri.toString().getBytes(StandardCharsets.UTF_8));

            StringBuilder hashStringBuilder = new StringBuilder();
            for (byte hashByte : hashBytes) {
                String hex = Integer.toHexString(0xff & hashByte);
                if (hex.length() == 1) {
                    hashStringBuilder.append('0');
                }
                hashStringBuilder.append(hex);
            }
            return hashStringBuilder.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }
}
