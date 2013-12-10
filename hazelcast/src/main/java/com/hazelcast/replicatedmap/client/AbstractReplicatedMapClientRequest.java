/*
 * Copyright (c) 2008-2013, Hazelcast, Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hazelcast.replicatedmap.client;

import com.hazelcast.client.CallableClientRequest;
import com.hazelcast.client.RetryableRequest;
import com.hazelcast.nio.serialization.Portable;
import com.hazelcast.nio.serialization.PortableReader;
import com.hazelcast.nio.serialization.PortableWriter;
import com.hazelcast.replicatedmap.ReplicatedMapService;
import com.hazelcast.replicatedmap.record.ReplicatedRecordStore;

import java.io.IOException;

public abstract class AbstractReplicatedMapClientRequest
        extends CallableClientRequest
        implements RetryableRequest, Portable {

    private String mapName;

    public AbstractReplicatedMapClientRequest(String mapName) {
        this.mapName = mapName;
    }

    public String getMapName() {
        return mapName;
    }

    public void setMapName(String mapName) {
        this.mapName = mapName;
    }

    @Override
    public String getServiceName() {
        return ReplicatedMapService.SERVICE_NAME;
    }

    @Override
    public void writePortable(PortableWriter writer) throws IOException {
        writer.writeUTF("mapName", mapName);
    }

    @Override
    public void readPortable(PortableReader reader) throws IOException {
        mapName = reader.readUTF("mapName");
    }

    @Override
    public int getFactoryId() {
        return ReplicatedMapPortableHook.F_ID;
    }

    protected ReplicatedRecordStore getReplicatedRecordStore() {
        ReplicatedMapService replicatedMapService = getService();
        return replicatedMapService.getReplicatedRecordStore(mapName, false);
    }

}
