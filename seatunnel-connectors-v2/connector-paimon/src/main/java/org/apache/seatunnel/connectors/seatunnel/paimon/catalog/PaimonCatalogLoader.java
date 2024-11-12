/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.seatunnel.connectors.seatunnel.paimon.catalog;

import org.apache.seatunnel.connectors.seatunnel.paimon.config.PaimonConfig;
import org.apache.seatunnel.connectors.seatunnel.paimon.config.PaimonHadoopConfiguration;
import org.apache.seatunnel.connectors.seatunnel.paimon.exception.PaimonConnectorErrorCode;
import org.apache.seatunnel.connectors.seatunnel.paimon.exception.PaimonConnectorException;
import org.apache.seatunnel.connectors.seatunnel.paimon.security.PaimonSecurityContext;

import org.apache.commons.lang3.StringUtils;
import org.apache.hadoop.conf.Configuration;
import org.apache.paimon.catalog.Catalog;
import org.apache.paimon.catalog.CatalogContext;
import org.apache.paimon.catalog.CatalogFactory;
import org.apache.paimon.options.CatalogOptions;
import org.apache.paimon.options.Options;

import lombok.extern.slf4j.Slf4j;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

@Slf4j
public class PaimonCatalogLoader implements Serializable {
    /** hdfs uri is required */
    private static final String HDFS_DEF_FS_NAME = "fs.defaultFS";

    private static final String HDFS_PREFIX = "hdfs://";
    /** ********* Hdfs constants ************* */
    private static final String HDFS_IMPL = "org.apache.hadoop.hdfs.DistributedFileSystem";

    private static final String HDFS_IMPL_KEY = "fs.hdfs.impl";

    private String warehouse;
    private PaimonCatalogEnum catalogType;
    private String catalogUri;

    private PaimonHadoopConfiguration paimonHadoopConfiguration;

    private Map<String, String> paimonFilesystemConfiguration;

    public PaimonCatalogLoader(PaimonConfig paimonConfig) {
        this.warehouse = paimonConfig.getWarehouse();
        this.catalogType = paimonConfig.getCatalogType();
        this.catalogUri = paimonConfig.getCatalogUri();
        this.paimonHadoopConfiguration = PaimonSecurityContext.loadHadoopConfig(paimonConfig);
        this.paimonFilesystemConfiguration = paimonConfig.getFsConfProps();
    }

    public Catalog loadCatalog() {
        // When using the seatunel engine, set the current class loader to prevent loading failures
        Thread.currentThread().setContextClassLoader(PaimonCatalogLoader.class.getClassLoader());
        final Map<String, String> optionsMap = new HashMap<>(1);
        optionsMap.put(CatalogOptions.WAREHOUSE.key(), warehouse);
        optionsMap.put(CatalogOptions.METASTORE.key(), catalogType.getType());
        if (warehouse.startsWith(HDFS_PREFIX)) {
            checkConfiguration(paimonHadoopConfiguration, HDFS_DEF_FS_NAME);
            paimonHadoopConfiguration.set(HDFS_IMPL_KEY, HDFS_IMPL);
        }
        if (PaimonCatalogEnum.HIVE.getType().equals(catalogType.getType())) {
            optionsMap.put(CatalogOptions.URI.key(), catalogUri);
            paimonHadoopConfiguration
                    .getPropsWithPrefix(StringUtils.EMPTY)
                    .forEach((k, v) -> optionsMap.put(k, v));
        } else if (PaimonCatalogEnum.FILESYSTEM.getType().equals(catalogType.getType())) {
            optionsMap.putAll(paimonFilesystemConfiguration);
        }
        final Options options = Options.fromMap(optionsMap);
        PaimonSecurityContext.shouldEnableKerberos(paimonHadoopConfiguration);
        final CatalogContext catalogContext =
                CatalogContext.create(options, paimonHadoopConfiguration);
        try {
            return PaimonSecurityContext.runSecured(
                    () -> CatalogFactory.createCatalog(catalogContext));
        } catch (Exception e) {
            throw new PaimonConnectorException(
                    PaimonConnectorErrorCode.LOAD_CATALOG,
                    "Failed to perform SecurityContext.runSecured",
                    e);
        }
    }

    void checkConfiguration(Configuration configuration, String key) {
        Iterator<Map.Entry<String, String>> entryIterator = configuration.iterator();
        while (entryIterator.hasNext()) {
            Map.Entry<String, String> entry = entryIterator.next();
            if (entry.getKey().equals(key)) {
                if (StringUtils.isBlank(entry.getValue())) {
                    throw new IllegalArgumentException("The value of" + key + " is required");
                }
                return;
            }
        }
        throw new IllegalArgumentException(key + " is required");
    }
}
