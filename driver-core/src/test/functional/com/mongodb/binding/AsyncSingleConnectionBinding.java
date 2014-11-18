/*
 * Copyright (c) 2008-2014 MongoDB, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.mongodb.binding;

import com.mongodb.ReadPreference;
import com.mongodb.async.MongoFuture;
import com.mongodb.async.SingleResultFuture;
import com.mongodb.connection.Cluster;
import com.mongodb.connection.Connection;
import com.mongodb.connection.Server;
import com.mongodb.connection.ServerDescription;
import com.mongodb.selector.PrimaryServerSelector;
import com.mongodb.selector.ReadPreferenceServerSelector;

import java.util.concurrent.TimeUnit;

import static com.mongodb.ReadPreference.primary;
import static com.mongodb.assertions.Assertions.isTrue;
import static com.mongodb.assertions.Assertions.notNull;

/**
 * An asynchronous binding that ensures that all reads use the same connection, and all writes use the same connection.
 *
 * <p>If the readPreference is {#link ReadPreference.primary()} then all reads and writes will use the same connection.</p>
 *
 */
public class AsyncSingleConnectionBinding extends AbstractReferenceCounted implements AsyncReadWriteBinding {
    private final ReadPreference readPreference;
    private final Connection readConnection;
    private final Connection writeConnection;
    private final Server readServer;
    private final Server writeServer;

    /**
     * Create a new binding with the given cluster.
     *
     * @param cluster     a non-null Cluster which will be used to select a server to bind to
     * @param maxWaitTime the maximum time to wait for a connection to become available.
     * @param timeUnit    a non-null TimeUnit for the maxWaitTime
     */
    public AsyncSingleConnectionBinding(final Cluster cluster, final long maxWaitTime, final TimeUnit timeUnit) {
        this(cluster, primary(), maxWaitTime, timeUnit);
    }

    /**
     * Create a new binding with the given cluster.
     *
     * @param cluster     a non-null Cluster which will be used to select a server to bind to
     * @param readPreference the readPreference for reads, if not primary a separate connection will be used for reads
     * @param maxWaitTime the maximum time to wait for a connection to become available
     * @param timeUnit    a non-null TimeUnit for the maxWaitTime
     */
    public AsyncSingleConnectionBinding(final Cluster cluster, final ReadPreference readPreference,
                                   final long maxWaitTime, final TimeUnit timeUnit) {
        notNull("cluster", cluster);
        notNull("timeUnit", timeUnit);
        this.readPreference = notNull("readPreference", readPreference);
        writeServer = cluster.selectServer(new PrimaryServerSelector(), maxWaitTime, timeUnit);
        writeConnection = writeServer.getConnection();
        readServer = cluster.selectServer(new ReadPreferenceServerSelector(readPreference), maxWaitTime, timeUnit);
        readConnection = readServer.getConnection();
    }

    @Override
    public AsyncReadWriteBinding retain() {
        super.retain();
        return this;
    }

    @Override
    public ReadPreference getReadPreference() {
        return readPreference;
    }

    @Override
    public MongoFuture<AsyncConnectionSource> getReadConnectionSource() {
        isTrue("open", getCount() > 0);
        if (readPreference == primary()) {
            return getWriteConnectionSource();
        } else {
            return new SingleResultFuture<AsyncConnectionSource>(new SingleAsyncConnectionSource(readServer, readConnection));
        }
    }

    @Override
    public MongoFuture<AsyncConnectionSource> getWriteConnectionSource() {
        isTrue("open", getCount() > 0);
        return new SingleResultFuture<AsyncConnectionSource>(new SingleAsyncConnectionSource(writeServer, writeConnection));
    }

    @Override
    public void release() {
        super.release();
        if (getCount() == 0) {
            readConnection.release();
            writeConnection.release();
        }
    }

    private final class SingleAsyncConnectionSource extends AbstractReferenceCounted implements AsyncConnectionSource {
        private final Server server;
        private final Connection connection;

        private SingleAsyncConnectionSource(final Server server, final Connection connection) {
            this.server = server;
            this.connection = connection;
            AsyncSingleConnectionBinding.this.retain();
        }

        @Override
        public ServerDescription getServerDescription() {
            return server.getDescription();
        }

        @Override
        public MongoFuture<Connection> getConnection() {
            isTrue("open", super.getCount() > 0);
            return new SingleResultFuture<Connection>(connection.retain());
        }

        public AsyncConnectionSource retain() {
            super.retain();
            return this;
        }

        @Override
        public void release() {
            super.release();
            if (super.getCount() == 0) {
                AsyncSingleConnectionBinding.this.release();
            }
        }
    }
}