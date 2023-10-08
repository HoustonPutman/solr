/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.solr.cloud;

import java.lang.invoke.MethodHandles;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.apache.solr.common.SolrException;
import org.apache.solr.common.SolrException.ErrorCode;
import org.apache.solr.common.cloud.SolrZkClient;
import org.apache.solr.common.cloud.ZkMaintenanceUtils;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.KeeperException.NodeExistsException;
import org.apache.zookeeper.data.Stat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A distributed map. This supports basic map functions e.g. get, put, contains for interaction with
 * zk which don't have to be ordered i.e. DistributedQueue.
 */
public class DistributedMap {

  private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

  protected final String dir;

  protected SolrZkClient zookeeper;

  protected static final String PREFIX = "mn-";

  public DistributedMap(SolrZkClient zookeeper, String dir) {
    this.dir = dir;

    try {
      ZkMaintenanceUtils.ensureExists(dir, zookeeper);
    } catch (KeeperException e) {
      throw new SolrException(ErrorCode.SERVER_ERROR, e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new SolrException(ErrorCode.SERVER_ERROR, e);
    }

    this.zookeeper = zookeeper;
  }

  private void assertKeyFormat(String trackingId) {
    if (trackingId == null || trackingId.length() == 0 || trackingId.contains("/")) {
      throw new SolrException(ErrorCode.BAD_REQUEST, "Unsupported key format: " + trackingId);
    }
  }

  public void put(String trackingId, byte[] data) throws KeeperException, InterruptedException {
    assertKeyFormat(trackingId);
    zookeeper.makePath(
        dir + "/" + PREFIX + trackingId, data, CreateMode.PERSISTENT, null, false);
  }

  /**
   * Puts an element in the map only if there isn't one with the same trackingId already
   *
   * @return True if the element was added. False if it wasn't (because the key already exists)
   */
  public boolean putIfAbsent(String trackingId, byte[] data)
      throws KeeperException, InterruptedException {
    assertKeyFormat(trackingId);
    try {
      zookeeper.makePath(
          dir + "/" + PREFIX + trackingId, data, CreateMode.PERSISTENT, null, true);
      return true;
    } catch (NodeExistsException e) {
      return false;
    }
  }

  public byte[] get(String trackingId) throws KeeperException, InterruptedException {
    return zookeeper.getData(dir + "/" + PREFIX + trackingId, null, null);
  }

  public boolean contains(String trackingId) throws KeeperException, InterruptedException {
    return zookeeper.exists(dir + "/" + PREFIX + trackingId);
  }

  public int size() throws KeeperException, InterruptedException {
    Stat stat = new Stat();
    zookeeper.getData(dir, null, stat);
    return stat.getNumChildren();
  }

  /**
   * return true if the znode was successfully deleted false if the node didn't exist and therefore
   * not deleted exception an exception occurred while deleting
   */
  public boolean remove(String trackingId) throws KeeperException, InterruptedException {
    final var path = dir + "/" + PREFIX + trackingId;
    try {
      zookeeper.delete(path, -1);
    } catch (KeeperException.NoNodeException e) {
      return false;
    } catch (KeeperException.NotEmptyException hack) {
      // because dirty data before we enforced the rules on put() (trackingId shouldn't have slash)
      log.warn("Cleaning malformed key ID starting with {}", path);
      zookeeper.clean(path);
    }
    return true;
  }

  /** Helper method to clear all child nodes for a parent node. */
  public void clear() throws KeeperException, InterruptedException {
    List<String> childNames = zookeeper.getChildren(dir, null);
    for (String childName : childNames) {
      zookeeper.delete(dir + "/" + childName, -1);
    }
  }

  /** Returns the keys of all the elements in the map */
  public Collection<String> keys() throws KeeperException, InterruptedException {
    List<String> childs = zookeeper.getChildren(dir, null);
    final List<String> ids = new ArrayList<>(childs.size());
    childs.stream().forEach((child) -> ids.add(child.substring(PREFIX.length())));
    return ids;
  }
}
