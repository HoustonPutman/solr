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
package org.apache.solr.api;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.apache.solr.client.solrj.request.CollectionAdminRequest;
import org.apache.solr.cloud.SolrCloudTestCase;
import org.apache.solr.embedded.JettySolrRunner;
import org.eclipse.jetty.client.ContentResponse;
import org.eclipse.jetty.client.StringRequestContent;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * SOLR-18487: V2 collection-admin requests sent to a node that hosts no replica of the target
 * collection take V2HttpCall's ADMIN_OR_REMOTEPROXY path, where no SolrQueryRequest is attached.
 */
public class V2CollectionAdminOnNodeWithoutCoreTest extends SolrCloudTestCase {

  @BeforeClass
  public static void setupCluster() throws Exception {
    configureCluster(2).addConfig("conf", configset("cloud-minimal")).configure();
  }

  /** Creates a 1x1 collection and returns the node that does not host its replica. */
  private static JettySolrRunner createAndGetNodeWithoutReplica(String collection)
      throws Exception {
    CollectionAdminRequest.createCollection(collection, "conf", 1, 1)
        .process(cluster.getSolrClient());
    waitForState("Timed out waiting for collection", collection, clusterShape(1, 1));
    String hostingNode =
        cluster
            .getZkStateReader()
            .getClusterState()
            .getCollection(collection)
            .getSlice("shard1")
            .getReplicas()
            .iterator()
            .next()
            .getNodeName();
    return cluster.getJettySolrRunners().stream()
        .filter(j -> !j.getNodeName().equals(hostingNode))
        .findFirst()
        .orElseThrow(() -> new AssertionError("Expected a node with no local replica"));
  }

  private static ContentResponse postJson(JettySolrRunner jetty, String v2Path, String json)
      throws Exception {
    String url = String.format(Locale.ROOT, "%s/____v2%s", jetty.getBaseUrl(), v2Path);
    return jetty
        .getSolrClient()
        .getHttpClient()
        .POST(url)
        .body(new StringRequestContent("application/json", json, StandardCharsets.UTF_8))
        .send();
  }

  @Test
  public void testReloadOnNodeWithoutLocalCore() throws Exception {
    String collection = "v2_reload_no_local_core";
    JettySolrRunner node = createAndGetNodeWithoutReplica(collection);
    ContentResponse rsp = postJson(node, "/collections/" + collection + "/reload", "{}");
    assertEquals("Body: " + rsp.getContentAsString(), 200, rsp.getStatus());
  }

  @Test
  public void testAddReplicaOnNodeWithoutLocalCore() throws Exception {
    String collection = "v2_addreplica_no_local_core";
    JettySolrRunner node = createAndGetNodeWithoutReplica(collection);
    ContentResponse rsp =
        postJson(node, "/collections/" + collection + "/shards/shard1/replicas", "{}");
    assertEquals("Body: " + rsp.getContentAsString(), 200, rsp.getStatus());
    waitForState("Timed out waiting for second replica", collection, clusterShape(1, 2));
  }
}
