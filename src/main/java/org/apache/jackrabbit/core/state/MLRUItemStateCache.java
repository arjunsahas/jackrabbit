/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.jackrabbit.core.state;

import org.apache.commons.collections.map.LRUMap;
import org.apache.commons.collections.map.LinkedMap;
import org.apache.jackrabbit.core.ItemId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;
import java.util.Collections;
import java.util.Collection;
import java.util.ArrayList;
import java.util.Iterator;

/**
 * An <code>ItemStateCache</code> implementation that internally uses a
 * {@link LRUMap} to maintain a cache of <code>ItemState</code> objects. the
 * cache uses a rough estimate of the memory consuption of the cache item
 * states for calculating the maximum number of entries.
 */
public class MLRUItemStateCache implements ItemStateCache, Cache {
    /** Logger instance */
    private static Logger log = LoggerFactory.getLogger(LRUItemStateCache.class);

    /** default maximum memory to use */
    public static final int DEFAULT_MAX_MEM = 4 * 1024 * 1024;

    /** the amount of memory the entries use */
    private long totalMem;

    /** the maximum of memory the cache may use */
    private long maxMem;

    /** the number of writes */
    private long numWrites = 0;

    /** the access count */
    private long accessCount = 0;

    /**
     * A cache for <code>ItemState</code> instances
     */
    private final LinkedMap cache = new LinkedMap();

    /**
     * Constructs a new, empty <code>ItemStateCache</code> with a maximum amount
     * of memory of {@link #DEFAULT_MAX_MEM}.
     */
    public MLRUItemStateCache() {
        this(DEFAULT_MAX_MEM);
    }

    /**
     * Constructs a new, empty <code>ItemStateCache</code> with the specified
     * maximum memory.
     *
     * @param maxMem the maximum amount of memory this cache may use.
     */
    public MLRUItemStateCache(int maxMem) {
        this.maxMem = maxMem;
        CacheManager.getInstance().add(this);
    }

    //-------------------------------------------------------< ItemStateCache >
    /**
     * {@inheritDoc}
     */
    public boolean isCached(ItemId id) {
        synchronized (cache) {
            return cache.containsKey(id);
        }
    }

    /**
     * {@inheritDoc}
     */
    public ItemState retrieve(ItemId id) {
        synchronized (cache) {
            touch();
            Entry entry = (Entry) cache.remove(id);
            if (entry != null) {
                // 'touch' item, by adding at end of list
                cache.put(id, entry);
                return entry.state;
            } else {
                return null;
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    public void update(ItemId id) {
        synchronized (cache) {
            touch();
            Entry entry = (Entry) cache.get(id);
            if (entry != null) {
                totalMem -= entry.size;
                entry.recalc();
                totalMem += entry.size;
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    public void cache(ItemState state) {
        synchronized (cache) {
            touch();
            ItemId id = state.getId();
            if (cache.containsKey(id)) {
                log.warn("overwriting cached entry " + id);
                evict(id);
            }
            Entry entry = new Entry(state);
            cache.put(id, entry);
            totalMem += entry.size;
            shrinkIfRequired();
            if (numWrites++%10000 == 0 && log.isDebugEnabled()) {
                log.info(this + " size=" + cache.size() + ", " + totalMem + "/" + maxMem);
            }
        }
    }

    private void shrinkIfRequired() {
        // remove items, if too many
        while (totalMem > maxMem) {
            ItemId id = (ItemId) cache.firstKey();
            evict(id);
        }
    }

    /**
     * {@inheritDoc}
     */
    public void evict(ItemId id) {
        synchronized (cache) {
            touch();
            Entry entry = (Entry) cache.remove(id);
            if (entry != null) {
                totalMem -= entry.size;
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    public void evictAll() {
        synchronized (cache) {
            cache.clear();
            totalMem = 0;
        }
    }

    /**
     * {@inheritDoc}
     */
    public boolean isEmpty() {
        synchronized (cache) {
            return cache.isEmpty();
        }
    }

    /**
     * {@inheritDoc}
     */
    public int size() {
        synchronized (cache) {
            return cache.size();
        }
    }

    /**
     * {@inheritDoc}
     */
    public Set keySet() {
        synchronized (cache) {
            return Collections.unmodifiableSet(cache.keySet());
        }
    }

    /**
     * {@inheritDoc}
     */
    public Collection values() {
        synchronized (cache) {
            ArrayList list = new ArrayList(cache.size());
            Iterator iter = cache.values().iterator();
            while (iter.hasNext()) {
                Entry entry = (Entry) iter.next();
                list.add(entry.state);
            }
            return list;
        }
    }

    private void touch() {
        accessCount++;
    }

    /**
     * {@inheritDoc}
     */
    public long getAccessCount() {
        return accessCount;
    }

    /**
     * {@inheritDoc}
     */
    public long getMaxMemorySize() {
        return maxMem;
    }

    /**
     * {@inheritDoc}
     */
    public long getMemoryUsed() {
        synchronized (cache) {
            totalMem = 0;
            Iterator iter = cache.values().iterator();
            while (iter.hasNext()) {
                Entry entry = (Entry) iter.next();
                entry.recalc();
                totalMem += entry.size;
            }
        }
        return totalMem;
    }

    /**
     * {@inheritDoc}
     */
    public void resetAccessCount() {
        synchronized (cache) {
            accessCount = 0;
        }
    }

    /**
     * {@inheritDoc}
     */
    public void setMaxMemorySize(long size) {
        synchronized (cache) {
            this.maxMem = size;
            shrinkIfRequired();
        }
    }

    /**
     * Internal cache entry
     */
    private static class Entry {

        private final ItemState state;

        private long size;

        public Entry(ItemState state) {
            this.state = state;
            this.size = 64 + state.calculateMemoryFootprint();
        }

        public void recalc() {
            size = 64 + state.calculateMemoryFootprint();
        }
    }
}
