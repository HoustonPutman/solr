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

package org.apache.solr.client.solrj.impl;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import org.apache.curator.framework.api.transaction.CuratorTransactionResult;
import org.apache.solr.client.solrj.cloud.AlreadyExistsException;
import org.apache.solr.client.solrj.cloud.BadVersionException;
import org.apache.solr.client.solrj.cloud.DistribStateManager;
import org.apache.solr.client.solrj.cloud.NotEmptyException;
import org.apache.solr.client.solrj.cloud.VersionedData;
import org.apache.solr.common.AlreadyClosedException;
import org.apache.solr.common.cloud.PerReplicaStates;
import org.apache.solr.common.cloud.PerReplicaStatesOps;
import org.apache.solr.common.cloud.SolrZkClient;
import org.apache.solr.common.util.Utils;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.data.Stat;

/** Implementation of {@link DistribStateManager} that uses Zookeeper. */
public class ZkDistribStateManager implements DistribStateManager {

  private final SolrZkClient zkClient;

  public ZkDistribStateManager(SolrZkClient zkClient) {
    this.zkClient = zkClient;
  }

  @SuppressWarnings({"unchecked"})
  @Override
  public Map<String, Object> getJson(String path)
      throws InterruptedException, IOException, KeeperException {
    VersionedData data;
    try {
      data = getData(path);
    } catch (KeeperException.NoNodeException | NoSuchElementException e) {
      return Collections.emptyMap();
    }
    if (data == null || data.getData() == null || data.getData().length == 0) {
      return Collections.emptyMap();
    }
    return (Map<String, Object>) Utils.fromJSON(data.getData());
  }

  @Override
  public boolean hasData(String path) throws IOException, KeeperException, InterruptedException {
    try {
      return zkClient.exists(path);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new AlreadyClosedException();
    }
  }

  @Override
  public List<String> listData(String path, Watcher watcher)
      throws NoSuchElementException, IOException, KeeperException, InterruptedException {
    try {
      return zkClient.getChildren(path, watcher);
    } catch (KeeperException.NoNodeException e) {
      throw new NoSuchElementException(path);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new AlreadyClosedException();
    }
  }

  @Override
  public List<String> listData(String path)
      throws NoSuchElementException, IOException, KeeperException, InterruptedException {
    return listData(path, null);
  }

  @Override
  public VersionedData getData(String path, Watcher watcher)
      throws NoSuchElementException, IOException, KeeperException, InterruptedException {
    Stat stat = new Stat();
    try {
      byte[] bytes = zkClient.getData(path, watcher, stat);
      return new VersionedData(
          stat.getVersion(),
          bytes,
          stat.getEphemeralOwner() != 0 ? CreateMode.EPHEMERAL : CreateMode.PERSISTENT,
          String.valueOf(stat.getEphemeralOwner()));
    } catch (KeeperException.NoNodeException e) {
      throw new NoSuchElementException(path);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new AlreadyClosedException();
    }
  }

  @Override
  public void makePath(String path)
      throws AlreadyExistsException, IOException, KeeperException, InterruptedException {
    try {
      zkClient.makePath(path);
    } catch (KeeperException.NodeExistsException e) {
      throw new AlreadyExistsException(path);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new AlreadyClosedException();
    }
  }

  @Override
  public void makePath(String path, byte[] data, CreateMode createMode, boolean failOnExists)
      throws AlreadyExistsException, IOException, KeeperException, InterruptedException {
    try {
      zkClient.makePath(path, data, createMode, null, failOnExists);
    } catch (KeeperException.NodeExistsException e) {
      throw new AlreadyExistsException(path);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new AlreadyClosedException();
    }
  }

  @Override
  public String createData(String path, byte[] data, CreateMode mode)
      throws NoSuchElementException, AlreadyExistsException, IOException, KeeperException,
          InterruptedException {
    try {
      return zkClient.create(path, data, mode);
    } catch (KeeperException.NoNodeException e) {
      throw new NoSuchElementException(path);
    } catch (KeeperException.NodeExistsException e) {
      throw new AlreadyExistsException(path);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new AlreadyClosedException();
    }
  }

  @Override
  public void removeData(String path, int version)
      throws NoSuchElementException, BadVersionException, NotEmptyException, IOException,
          KeeperException, InterruptedException {
    try {
      zkClient.delete(path, version);
    } catch (KeeperException.NoNodeException e) {
      throw new NoSuchElementException(path);
    } catch (KeeperException.NotEmptyException e) {
      throw new NotEmptyException(path);
    } catch (KeeperException.BadVersionException e) {
      throw new BadVersionException(version, path);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new AlreadyClosedException();
    }
  }

  @Override
  public void setData(String path, byte[] data, int version)
      throws BadVersionException, NoSuchElementException, IOException, KeeperException,
          InterruptedException {
    try {
      zkClient.setData(path, data, version);
    } catch (KeeperException.NoNodeException e) {
      throw new NoSuchElementException(path);
    } catch (KeeperException.BadVersionException e) {
      throw new BadVersionException(version, path);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new AlreadyClosedException();
    }
  }

  @Override
  public List<CuratorTransactionResult> multi(List<SolrZkClient.CuratorOpBuilder> ops)
      throws BadVersionException, AlreadyExistsException, NoSuchElementException, IOException,
          KeeperException, InterruptedException {
    try {
      return zkClient.multi(ops);
    } catch (KeeperException.NoNodeException e) {
      throw new NoSuchElementException(ops.toString());
    } catch (KeeperException.NodeExistsException e) {
      throw new AlreadyExistsException(ops.toString());
    } catch (KeeperException.BadVersionException e) {
      throw new BadVersionException(-1, ops.toString());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new AlreadyClosedException();
    }
  }

  @Override
  public void close() throws IOException {}

  public SolrZkClient getZkClient() {
    return zkClient;
  }

  @Override
  public PerReplicaStates getReplicaStates(String path)
      throws KeeperException, InterruptedException {
    return PerReplicaStatesOps.fetch(path, zkClient, null);
  }
}
