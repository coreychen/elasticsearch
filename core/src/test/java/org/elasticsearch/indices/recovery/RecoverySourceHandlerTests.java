/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.elasticsearch.indices.recovery;

import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.*;
import org.apache.lucene.store.*;
import org.apache.lucene.util.IOUtils;
import org.elasticsearch.ExceptionsHelper;
import org.elasticsearch.Version;
import org.elasticsearch.cluster.node.DiscoveryNode;
import org.elasticsearch.common.io.FileSystemUtils;
import org.elasticsearch.common.lucene.store.IndexOutputOutputStream;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.transport.DummyTransportAddress;
import org.elasticsearch.index.Index;
import org.elasticsearch.index.shard.ShardId;
import org.elasticsearch.index.store.DirectoryService;
import org.elasticsearch.index.store.Store;
import org.elasticsearch.index.store.StoreFileMetaData;
import org.elasticsearch.node.settings.NodeSettingsService;
import org.elasticsearch.test.DummyShardLock;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.test.CorruptionUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.hamcrest.Matchers.is;

public class RecoverySourceHandlerTests extends ESTestCase {

    private final ShardId shardId = new ShardId(new Index("index"), 1);
    private final NodeSettingsService service = new NodeSettingsService(Settings.EMPTY);

    public void testSendFiles() throws Throwable {
        final RecoverySettings recoverySettings = new RecoverySettings(Settings.EMPTY, service);
        StartRecoveryRequest request = new StartRecoveryRequest(shardId,
                new DiscoveryNode("b", DummyTransportAddress.INSTANCE, Version.CURRENT),
                new DiscoveryNode("b", DummyTransportAddress.INSTANCE, Version.CURRENT),
                randomBoolean(), null, RecoveryState.Type.STORE, randomLong());
        Store store = newStore(createTempDir());
        RecoverySourceHandler handler = new RecoverySourceHandler(null, request, recoverySettings, null, logger);
        Directory dir = store.directory();
        RandomIndexWriter writer = new RandomIndexWriter(random(), dir, newIndexWriterConfig());
        int numDocs = randomIntBetween(10, 100);
        for (int i = 0; i < numDocs; i++) {
            Document document = new Document();
            document.add(new StringField("id", Integer.toString(i), Field.Store.YES));
            document.add(newField("field", randomUnicodeOfCodepointLengthBetween(1, 10), TextField.TYPE_STORED));
            writer.addDocument(document);
        }
        writer.commit();
        Store.MetadataSnapshot metadata = store.getMetadata();
        List<StoreFileMetaData> metas = new ArrayList<>();
        for (StoreFileMetaData md : metadata) {
            metas.add(md);
        }
        Store targetStore = newStore(createTempDir());
        handler.sendFiles(store, metas.toArray(new StoreFileMetaData[0]), (md) -> {
            try {
                return new IndexOutputOutputStream(targetStore.createVerifyingOutput(md.name(), md, IOContext.DEFAULT));
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
        Store.MetadataSnapshot targetStoreMetadata = targetStore.getMetadata();
        Store.RecoveryDiff recoveryDiff = targetStoreMetadata.recoveryDiff(metadata);
        assertEquals(metas.size(), recoveryDiff.identical.size());
        assertEquals(0, recoveryDiff.different.size());
        assertEquals(0, recoveryDiff.missing.size());
        IndexReader reader = DirectoryReader.open(targetStore.directory());
        assertEquals(numDocs, reader.maxDoc());
        IOUtils.close(reader, writer, store, targetStore, recoverySettings);
    }

    public void testHandleCorruptedIndexOnSendSendFiles() throws Throwable {
        final RecoverySettings recoverySettings = new RecoverySettings(Settings.EMPTY, service);
        StartRecoveryRequest request = new StartRecoveryRequest(shardId,
                new DiscoveryNode("b", DummyTransportAddress.INSTANCE, Version.CURRENT),
                new DiscoveryNode("b", DummyTransportAddress.INSTANCE, Version.CURRENT),
                randomBoolean(), null, RecoveryState.Type.STORE, randomLong());
        Path tempDir = createTempDir();
        Store store = newStore(tempDir);
        AtomicBoolean failedEngine = new AtomicBoolean(false);
        RecoverySourceHandler handler = new RecoverySourceHandler(null, request, recoverySettings, null, logger) {
            @Override
            protected void failEngine(IOException cause) {
                assertFalse(failedEngine.get());
                failedEngine.set(true);
            }
        };
        Directory dir = store.directory();
        RandomIndexWriter writer = new RandomIndexWriter(random(), dir, newIndexWriterConfig());
        int numDocs = randomIntBetween(10, 100);
        for (int i = 0; i < numDocs; i++) {
            Document document = new Document();
            document.add(new StringField("id", Integer.toString(i), Field.Store.YES));
            document.add(newField("field", randomUnicodeOfCodepointLengthBetween(1, 10), TextField.TYPE_STORED));
            writer.addDocument(document);
        }
        writer.commit();
        writer.close();

        Store.MetadataSnapshot metadata = store.getMetadata();
        List<StoreFileMetaData> metas = new ArrayList<>();
        for (StoreFileMetaData md : metadata) {
            metas.add(md);
        }
        CorruptionUtils.corruptFile(getRandom(), FileSystemUtils.files(tempDir, (p) ->
                (p.getFileName().toString().equals("write.lock") ||
                        Files.isDirectory(p)) == false));
        Store targetStore = newStore(createTempDir());
        try {
            handler.sendFiles(store, metas.toArray(new StoreFileMetaData[0]), (md) -> {
                try {
                    return new IndexOutputOutputStream(targetStore.createVerifyingOutput(md.name(), md, IOContext.DEFAULT));
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
            fail("corrupted index");
        } catch (IOException ex) {
            assertNotNull(ExceptionsHelper.unwrapCorruption(ex));
        }
        assertTrue(failedEngine.get());
        IOUtils.close(store, targetStore, recoverySettings);
    }

    public void testHandleExceptinoOnSendSendFiles() throws Throwable {
        final RecoverySettings recoverySettings = new RecoverySettings(Settings.EMPTY, service);
        StartRecoveryRequest request = new StartRecoveryRequest(shardId,
                new DiscoveryNode("b", DummyTransportAddress.INSTANCE, Version.CURRENT),
                new DiscoveryNode("b", DummyTransportAddress.INSTANCE, Version.CURRENT),
                randomBoolean(), null, RecoveryState.Type.STORE, randomLong());
        Path tempDir = createTempDir();
        Store store = newStore(tempDir);
        AtomicBoolean failedEngine = new AtomicBoolean(false);
        RecoverySourceHandler handler = new RecoverySourceHandler(null, request, recoverySettings, null, logger) {
            @Override
            protected void failEngine(IOException cause) {
                assertFalse(failedEngine.get());
                failedEngine.set(true);
            }
        };
        Directory dir = store.directory();
        RandomIndexWriter writer = new RandomIndexWriter(random(), dir, newIndexWriterConfig());
        int numDocs = randomIntBetween(10, 100);
        for (int i = 0; i < numDocs; i++) {
            Document document = new Document();
            document.add(new StringField("id", Integer.toString(i), Field.Store.YES));
            document.add(newField("field", randomUnicodeOfCodepointLengthBetween(1, 10), TextField.TYPE_STORED));
            writer.addDocument(document);
        }
        writer.commit();
        writer.close();

        Store.MetadataSnapshot metadata = store.getMetadata();
        List<StoreFileMetaData> metas = new ArrayList<>();
        for (StoreFileMetaData md : metadata) {
            metas.add(md);
        }
        final boolean throwCorruptedIndexException = randomBoolean();
        Store targetStore = newStore(createTempDir());
        try {
            handler.sendFiles(store, metas.toArray(new StoreFileMetaData[0]), (md) -> {
                if (throwCorruptedIndexException) {
                    throw new RuntimeException(new CorruptIndexException("foo", "bar"));
                } else {
                    throw new RuntimeException("boom");
                }
            });
            fail("exception index");
        } catch (RuntimeException ex) {
            assertNull(ExceptionsHelper.unwrapCorruption(ex));
            if (throwCorruptedIndexException) {
                assertEquals(ex.getMessage(), "[File corruption occurred on recovery but checksums are ok]");
            } else {
                assertEquals(ex.getMessage(), "boom");
            }
        } catch (CorruptIndexException ex) {
            fail("not expected here");
        }
        assertFalse(failedEngine.get());
        IOUtils.close(store, targetStore, recoverySettings);
    }

    private Store newStore(Path path) throws IOException {
        DirectoryService directoryService = new DirectoryService(shardId, Settings.EMPTY) {
            @Override
            public long throttleTimeInNanos() {
                return 0;
            }

            @Override
            public Directory newDirectory() throws IOException {
                return RecoverySourceHandlerTests.newFSDirectory(path);
            }
        };
        return new Store(shardId, Settings.EMPTY, directoryService, new DummyShardLock(shardId));
    }


}
