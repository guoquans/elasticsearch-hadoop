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
package org.elasticsearch.hadoop.cascading;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.elasticsearch.hadoop.cfg.Settings;
import org.elasticsearch.hadoop.cfg.SettingsManager;
import org.elasticsearch.hadoop.mr.Counters;
import org.elasticsearch.hadoop.mr.WritableBytesConverter;
import org.elasticsearch.hadoop.rest.InitializationUtils;
import org.elasticsearch.hadoop.rest.RestRepository;
import org.elasticsearch.hadoop.rest.ScrollQuery;
import org.elasticsearch.hadoop.rest.stats.Stats;
import org.elasticsearch.hadoop.serialization.builder.JdkValueReader;
import org.elasticsearch.hadoop.util.FieldAlias;
import org.elasticsearch.hadoop.util.SettingsUtils;
import org.elasticsearch.hadoop.util.StringUtils;

import cascading.flow.FlowProcess;
import cascading.scheme.Scheme;
import cascading.scheme.SinkCall;
import cascading.scheme.SourceCall;
import cascading.tap.Tap;
import cascading.tuple.Fields;
import cascading.tuple.Tuple;
import cascading.tuple.TupleEntry;

/**
 * Cascading Scheme handling
 */
class EsLocalScheme extends Scheme<Properties, ScrollQuery, Object, Object[], Object[]> {

    private static final long serialVersionUID = 979036202776892844L;

    private final String resource;
    private final String query;
    private final String host;
    private final int port;
    private final Properties props;
    private transient RestRepository client;

    private boolean IS_ES_10;

    EsLocalScheme(String host, int port, String index, String query, Fields fields, Properties props) {
        this.resource = index;
        this.query = query;
        this.host = host;
        this.port = port;
        if (fields != null) {
            setSinkFields(fields);
            setSourceFields(fields);
        }
        this.props = props;
    }

    @Override
    public void sourcePrepare(FlowProcess<Properties> flowProcess, SourceCall<Object[], ScrollQuery> sourceCall) throws IOException {
        super.sourcePrepare(flowProcess, sourceCall);

        Object[] context = new Object[1];
        Settings settings = SettingsManager.loadFrom(flowProcess.getConfigCopy()).merge(props);
        context[0] = CascadingUtils.alias(settings);
        sourceCall.setContext(context);
        IS_ES_10 = SettingsUtils.isEs10(settings);
    }

    @Override
    public void sourceCleanup(FlowProcess<Properties> flowProcess, SourceCall<Object[], ScrollQuery> sourceCall) throws IOException {
        report(sourceCall.getInput().stats(), flowProcess);
        report(sourceCall.getInput().repository().stats(), flowProcess);
        sourceCall.getInput().close();
        sourceCall.setContext(null);
        cleanupClient(flowProcess);
    }

    @Override
    public void sinkCleanup(FlowProcess<Properties> flowProcess, SinkCall<Object[], Object> sinkCall) throws IOException {
        cleanupClient(flowProcess);
    }

    private void cleanupClient(FlowProcess<Properties> flowProcess) throws IOException {
        if (client != null) {
            report(client.stats(), flowProcess);
            client.close();
            client = null;
        }
    }

    private void report(Stats stats, FlowProcess<Properties> flowProcess) {
        // report current stats
        flowProcess.increment(Counters.BYTES_WRITTEN, stats.bytesWritten);
        flowProcess.increment(Counters.BYTES_READ, stats.bytesRead);
        flowProcess.increment(Counters.BYTES_RETRIED, stats.bytesRetried);
        flowProcess.increment(Counters.BYTES_RECORDED, stats.bytesRecorded);

        flowProcess.increment(Counters.DOCS_WRITTEN, stats.docsWritten);
        flowProcess.increment(Counters.DOCS_READ, stats.docsRead);
        flowProcess.increment(Counters.DOCS_RETRIED, stats.docsRetried);
        flowProcess.increment(Counters.DOCS_RECORDED, stats.docsRecorded);

        flowProcess.increment(Counters.BULK_WRITES, stats.bulkWrites);
        flowProcess.increment(Counters.BULK_RETRIES, stats.bulkRetries);
        flowProcess.increment(Counters.NODE_RETRIES, stats.nodeRetries);
        flowProcess.increment(Counters.NET_RETRIES, stats.netRetries);
    }

    @Override
    public void sinkPrepare(FlowProcess<Properties> flowProcess, SinkCall<Object[], Object> sinkCall) throws IOException {
        super.sinkPrepare(flowProcess, sinkCall);

        Object[] context = new Object[1];
        Settings settings = SettingsManager.loadFrom(flowProcess.getConfigCopy()).merge(props);
        context[0] = CascadingUtils.fieldToAlias(settings, getSinkFields());
        sinkCall.setContext(context);
    }

    @Override
    public void sourceConfInit(FlowProcess<Properties> flowProcess, Tap<Properties, ScrollQuery, Object> tap, Properties conf) {
        initClient(conf, true);
    }

    @Override
    public void sinkConfInit(FlowProcess<Properties> flowProcess, Tap<Properties, ScrollQuery, Object> tap, Properties conf) {
        initClient(conf, false);
        InitializationUtils.checkIndexExistence(SettingsManager.loadFrom(conf).merge(props), client);
    }

    private void initClient(Properties props, boolean read) {
        if (client == null) {
            Settings settings = SettingsManager.loadFrom(props).merge(this.props);
            CascadingUtils.init(settings, host, port, resource, query, read);

            Log log = LogFactory.getLog(EsTap.class);
            InitializationUtils.setValueWriterIfNotSet(settings, CascadingValueWriter.class, log);
            InitializationUtils.setValueReaderIfNotSet(settings, JdkValueReader.class, log);
            InitializationUtils.setBytesConverterIfNeeded(settings, WritableBytesConverter.class, log);
            InitializationUtils.setFieldExtractorIfNotSet(settings, CascadingLocalFieldExtractor.class, log);
            client = new RestRepository(settings);
        }
    }

    @SuppressWarnings("unchecked")
    @Override
    public boolean source(FlowProcess<Properties> flowProcess, SourceCall<Object[], ScrollQuery> sourceCall) throws IOException {
        ScrollQuery query = sourceCall.getInput();

        if (!query.hasNext()) {
            return false;
        }

        TupleEntry entry = sourceCall.getIncomingEntry();
        Map<String, ?> data = (Map<String, ?>) query.next()[1];
        FieldAlias alias = (FieldAlias) sourceCall.getContext()[0];

        if (entry.getFields().isDefined()) {
            // lookup using writables
            // TODO: it's worth benchmarking whether using an index/offset yields significantly better performance
            for (Comparable<?> field : entry.getFields()) {
                if (IS_ES_10) {
                    Object result = data;
                    // check for multi-level alias
                    for (String level : StringUtils.tokenize(alias.toES(field.toString()), ".")) {
                        result = ((Map) result).get(level);
                        if (result == null) {
                            break;
                        }
                    }
                    entry.setObject(field, result);
                }
                else {
                    //NB: coercion should be applied automatically by the TupleEntry
                    entry.setObject(field, data.get(alias.toES(field.toString())));
                }
            }
        }
        else {
            // no definition means no coercion
            List<Object> elements = Tuple.elements(entry.getTuple());
            elements.clear();
            elements.addAll(data.values());
        }
        return true;
    }

    @Override
    public void sink(FlowProcess<Properties> flowProcess, SinkCall<Object[], Object> sinkCall) throws IOException {
        client.writeToIndex(sinkCall);
    }
}