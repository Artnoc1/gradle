/*
 * Copyright 2019 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.internal.vfs.impl;

import com.google.common.collect.Interner;
import com.sun.nio.file.SensitivityWatchEventModifier;
import org.gradle.internal.file.Stat;
import org.gradle.internal.hash.FileHasher;
import org.gradle.internal.snapshot.CaseSensitivity;
import org.gradle.internal.vfs.WatchingVirtualFileSystem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.Collections;

import static java.nio.file.StandardWatchEventKinds.ENTRY_CREATE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_DELETE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY;
import static java.nio.file.StandardWatchEventKinds.OVERFLOW;

public class DefaultWatchingVirtualFileSystem extends DefaultVirtualFileSystem implements WatchingVirtualFileSystem, Closeable {
    private static final Logger LOGGER = LoggerFactory.getLogger(DefaultWatchingVirtualFileSystem.class);

    private final WatchService watchService;

    public DefaultWatchingVirtualFileSystem(
        FileHasher hasher,
        Interner<String> stringInterner,
        Stat stat,
        CaseSensitivity caseSensitivity,
        String... defaultExcludes
    ) {
        super(hasher, stringInterner, stat, caseSensitivity, defaultExcludes);
        try {
            this.watchService = FileSystems.getDefault().newWatchService();
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    @Override
    public void startWatching() {
        root.get().visitKnownDirectories(directory -> {
            try {
                Path path = directory.toPath();
                if (!Files.exists(path)) {
                    // TODO Technically this shouldn't be needed
                    return;
                }
                LOGGER.debug("Start watching {}", path);
                path.register(watchService,
                    new WatchEvent.Kind[]{ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY, OVERFLOW},
                    SensitivityWatchEventModifier.HIGH);
            } catch (IOException ex) {
                throw new UncheckedIOException(ex);
            }
        });
    }

    @Override
    public void stopWatching() {
        boolean overflow = false;
        while (!overflow) {
            WatchKey watchKey = watchService.poll();
            if (watchKey == null) {
                break;
            }
            watchKey.cancel();
            Path watchRoot = (Path) watchKey.watchable();
            LOGGER.debug("Stop watching {}", watchRoot);
            for (WatchEvent<?> event : watchKey.pollEvents()) {
                WatchEvent.Kind<?> kind = event.kind();
                if (kind == OVERFLOW) {
                    LOGGER.info("Too many modifications for path {} since last build, dropping all VFS state", watchRoot);
                    invalidateAll();
                    overflow = true;
                    break;
                }
                Path changedPath = watchRoot.resolve(((Path) event.context()));
                update(Collections.singleton(changedPath.toString()), () -> {});
            }
        }
    }

    @Override
    public void close() throws IOException {
        watchService.close();
    }
}
