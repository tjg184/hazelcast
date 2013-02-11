/*
 * Copyright (c) 2008-2012, Hazelcast, Inc. All Rights Reserved.
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

package com.hazelcast.jmx;

import com.hazelcast.core.MultiMap;

/**
 * @ali 2/11/13
 */
@ManagedDescription("MultiMap")
public class MultiMapMBean extends HazelcastMBean<MultiMap> {

    protected MultiMapMBean(MultiMap managedObject, ManagementService service) {
        super(managedObject, service);
        objectName = createObjectName("MultiMap", managedObject.getName());
    }

    @ManagedAnnotation("name")
    public String getName(){
        return managedObject.getName();
    }

    @ManagedAnnotation(value = "clear", operation = true)
    public void clear(){
        managedObject.clear();
    }

    @ManagedAnnotation("size")
    public int getSize(){
        return managedObject.size();
    }
}
