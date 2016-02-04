/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.yarn.server.timelineservice.storage;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.TreeMap;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.service.AbstractService;
import org.apache.hadoop.yarn.api.records.timelineservice.TimelineEntity;
import org.apache.hadoop.yarn.api.records.timelineservice.TimelineEvent;
import org.apache.hadoop.yarn.api.records.timelineservice.TimelineMetric;
import org.apache.hadoop.yarn.conf.YarnConfiguration;
import org.apache.hadoop.yarn.server.timelineservice.reader.TimelineDataToRetrieve;
import org.apache.hadoop.yarn.server.timelineservice.reader.TimelineEntityFilters;
import org.apache.hadoop.yarn.server.timelineservice.reader.TimelineReaderContext;
import org.apache.hadoop.yarn.server.timelineservice.storage.common.TimelineStorageUtils;
import org.apache.hadoop.yarn.webapp.YarnJacksonJaxbJsonProvider;
import org.codehaus.jackson.JsonGenerationException;
import org.codehaus.jackson.map.JsonMappingException;
import org.codehaus.jackson.map.ObjectMapper;

import com.google.common.annotations.VisibleForTesting;

/**
 *  File System based implementation for TimelineReader.
 */
public class FileSystemTimelineReaderImpl extends AbstractService
    implements TimelineReader {

  private static final Log LOG =
      LogFactory.getLog(FileSystemTimelineReaderImpl.class);

  private String rootPath;
  private static final String ENTITIES_DIR = "entities";

  /** Default extension for output files. */
  private static final String TIMELINE_SERVICE_STORAGE_EXTENSION = ".thist";

  @VisibleForTesting
  /** Default extension for output files. */
  static final String APP_FLOW_MAPPING_FILE = "app_flow_mapping.csv";

  @VisibleForTesting
  /** Config param for timeline service file system storage root. */
  static final String TIMELINE_SERVICE_STORAGE_DIR_ROOT =
      YarnConfiguration.TIMELINE_SERVICE_PREFIX + "fs-writer.root-dir";

  @VisibleForTesting
  /** Default value for storage location on local disk. */
  static final String DEFAULT_TIMELINE_SERVICE_STORAGE_DIR_ROOT =
      "/tmp/timeline_service_data";

  private final CSVFormat csvFormat =
      CSVFormat.DEFAULT.withHeader("APP", "USER", "FLOW", "FLOWRUN");

  public FileSystemTimelineReaderImpl() {
    super(FileSystemTimelineReaderImpl.class.getName());
  }

  @VisibleForTesting
  String getRootPath() {
    return rootPath;
  }

  private static ObjectMapper mapper;

  static {
    mapper = new ObjectMapper();
    YarnJacksonJaxbJsonProvider.configObjectMapper(mapper);
  }

  /**
   * Deserialize a POJO object from a JSON string.
   * @param clazz
   *      class to be desirialized
   *
   * @param jsonString
   *    json string to deserialize
   * @return TimelineEntity object
   * @throws IOException
   * @throws JsonMappingException
   * @throws JsonGenerationException
   */
  public static <T> T getTimelineRecordFromJSON(
      String jsonString, Class<T> clazz)
      throws JsonGenerationException, JsonMappingException, IOException {
    return mapper.readValue(jsonString, clazz);
  }

  private static void fillFields(TimelineEntity finalEntity,
      TimelineEntity real, EnumSet<Field> fields) {
    if (fields.contains(Field.ALL)) {
      fields = EnumSet.allOf(Field.class);
    }
    for (Field field : fields) {
      switch(field) {
        case CONFIGS:
          finalEntity.setConfigs(real.getConfigs());
          break;
        case METRICS:
          finalEntity.setMetrics(real.getMetrics());
          break;
        case INFO:
          finalEntity.setInfo(real.getInfo());
          break;
        case IS_RELATED_TO:
          finalEntity.setIsRelatedToEntities(real.getIsRelatedToEntities());
          break;
        case RELATES_TO:
          finalEntity.setIsRelatedToEntities(real.getIsRelatedToEntities());
          break;
        case EVENTS:
          finalEntity.setEvents(real.getEvents());
          break;
        default:
          continue;
      }
    }
  }

  private String getFlowRunPath(String userId, String clusterId, String flowName,
      Long flowRunId, String appId)
      throws IOException {
    if (userId != null && flowName != null && flowRunId != null) {
      return userId + "/" + flowName + "/" + flowRunId;
    }
    if (clusterId == null || appId == null) {
      throw new IOException("Unable to get flow info");
    }
    String appFlowMappingFile = rootPath + "/" +  ENTITIES_DIR + "/" +
        clusterId + "/" + APP_FLOW_MAPPING_FILE;
    try (BufferedReader reader =
             new BufferedReader(new InputStreamReader(
                 new FileInputStream(
                     appFlowMappingFile), Charset.forName("UTF-8")));
         CSVParser parser = new CSVParser(reader, csvFormat)) {
      for (CSVRecord record : parser.getRecords()) {
        if (record.size() < 4) {
          continue;
        }
        String applicationId = record.get("APP");
        if (applicationId != null && !applicationId.trim().isEmpty() &&
            !applicationId.trim().equals(appId)) {
          continue;
        }
        return record.get(1).trim() + "/" + record.get(2).trim() + "/" +
            record.get(3).trim();
      }
      parser.close();
    }
    throw new IOException("Unable to get flow info");
  }

  private static TimelineEntity createEntityToBeReturned(TimelineEntity entity,
      EnumSet<Field> fieldsToRetrieve) {
    TimelineEntity entityToBeReturned = new TimelineEntity();
    entityToBeReturned.setIdentifier(entity.getIdentifier());
    entityToBeReturned.setCreatedTime(entity.getCreatedTime());
    if (fieldsToRetrieve != null) {
      fillFields(entityToBeReturned, entity, fieldsToRetrieve);
    }
    return entityToBeReturned;
  }

  private static boolean isTimeInRange(Long time, Long timeBegin,
      Long timeEnd) {
    return (time >= timeBegin) && (time <= timeEnd);
  }

  private static void mergeEntities(TimelineEntity entity1,
      TimelineEntity entity2) {
    // Ideally created time wont change except in the case of issue from client.
    if (entity2.getCreatedTime() > 0) {
      entity1.setCreatedTime(entity2.getCreatedTime());
    }
    for (Entry<String, String> configEntry : entity2.getConfigs().entrySet()) {
      entity1.addConfig(configEntry.getKey(), configEntry.getValue());
    }
    for (Entry<String, Object> infoEntry : entity2.getInfo().entrySet()) {
      entity1.addInfo(infoEntry.getKey(), infoEntry.getValue());
    }
    for (Entry<String, Set<String>> isRelatedToEntry :
        entity2.getIsRelatedToEntities().entrySet()) {
      String type = isRelatedToEntry.getKey();
      for (String entityId : isRelatedToEntry.getValue()) {
        entity1.addIsRelatedToEntity(type, entityId);
      }
    }
    for (Entry<String, Set<String>> relatesToEntry :
        entity2.getRelatesToEntities().entrySet()) {
      String type = relatesToEntry.getKey();
      for (String entityId : relatesToEntry.getValue()) {
        entity1.addRelatesToEntity(type, entityId);
      }
    }
    for (TimelineEvent event : entity2.getEvents()) {
      entity1.addEvent(event);
    }
    for (TimelineMetric metric2 : entity2.getMetrics()) {
      boolean found = false;
      for (TimelineMetric metric1 : entity1.getMetrics()) {
        if (metric1.getId().equals(metric2.getId())) {
          metric1.addValues(metric2.getValues());
          found = true;
          break;
        }
      }
      if (!found) {
        entity1.addMetric(metric2);
      }
    }
  }

  private static TimelineEntity readEntityFromFile(BufferedReader reader)
      throws IOException {
    TimelineEntity entity =
        getTimelineRecordFromJSON(reader.readLine(), TimelineEntity.class);
    String entityStr = "";
    while ((entityStr = reader.readLine()) != null) {
      if (entityStr.trim().isEmpty()) {
        continue;
      }
      TimelineEntity anotherEntity =
          getTimelineRecordFromJSON(entityStr, TimelineEntity.class);
      if (!entity.getId().equals(anotherEntity.getId()) ||
          !entity.getType().equals(anotherEntity.getType())) {
        continue;
      }
      mergeEntities(entity, anotherEntity);
    }
    return entity;
  }

  private Set<TimelineEntity> getEntities(File dir, String entityType,
      TimelineEntityFilters filters, TimelineDataToRetrieve dataToRetrieve)
      throws IOException {
    // First sort the selected entities based on created/start time.
    Map<Long, Set<TimelineEntity>> sortedEntities =
        new TreeMap<>(
            new Comparator<Long>() {
              @Override
              public int compare(Long l1, Long l2) {
                return l2.compareTo(l1);
              }
            }
        );
    for (File entityFile : dir.listFiles()) {
      if (!entityFile.getName().contains(TIMELINE_SERVICE_STORAGE_EXTENSION)) {
        continue;
      }
      try (BufferedReader reader =
               new BufferedReader(
                   new InputStreamReader(
                       new FileInputStream(
                           entityFile), Charset.forName("UTF-8")))) {
        TimelineEntity entity = readEntityFromFile(reader);
        if (!entity.getType().equals(entityType)) {
          continue;
        }
        if (!isTimeInRange(entity.getCreatedTime(),
            filters.getCreatedTimeBegin(), filters.getCreatedTimeEnd())) {
          continue;
        }
        if (filters.getRelatesTo() != null &&
            !filters.getRelatesTo().isEmpty() &&
            !TimelineStorageUtils.matchRelations(
            entity.getRelatesToEntities(), filters.getRelatesTo())) {
          continue;
        }
        if (filters.getIsRelatedTo()  != null &&
            !filters.getIsRelatedTo().isEmpty() &&
            !TimelineStorageUtils.matchRelations(
            entity.getIsRelatedToEntities(), filters.getIsRelatedTo())) {
          continue;
        }
        if (filters.getInfoFilters() != null &&
            !filters.getInfoFilters().isEmpty() &&
            !TimelineStorageUtils.matchFilters(
            entity.getInfo(), filters.getInfoFilters())) {
          continue;
        }
        if (filters.getConfigFilters() != null &&
            !filters.getConfigFilters().isEmpty() &&
            !TimelineStorageUtils.matchFilters(
            entity.getConfigs(), filters.getConfigFilters())) {
          continue;
        }
        if (filters.getMetricFilters() != null &&
            !filters.getMetricFilters().isEmpty() &&
            !TimelineStorageUtils.matchMetricFilters(
            entity.getMetrics(), filters.getMetricFilters())) {
          continue;
        }
        if (filters.getEventFilters() != null &&
            !filters.getEventFilters().isEmpty() &&
            !TimelineStorageUtils.matchEventFilters(
            entity.getEvents(), filters.getEventFilters())) {
          continue;
        }
        TimelineEntity entityToBeReturned = createEntityToBeReturned(
            entity, dataToRetrieve.getFieldsToRetrieve());
        Set<TimelineEntity> entitiesCreatedAtSameTime =
            sortedEntities.get(entityToBeReturned.getCreatedTime());
        if (entitiesCreatedAtSameTime == null) {
          entitiesCreatedAtSameTime = new HashSet<TimelineEntity>();
        }
        entitiesCreatedAtSameTime.add(entityToBeReturned);
        sortedEntities.put(
            entityToBeReturned.getCreatedTime(), entitiesCreatedAtSameTime);
      }
    }

    Set<TimelineEntity> entities = new HashSet<TimelineEntity>();
    long entitiesAdded = 0;
    for (Set<TimelineEntity> entitySet : sortedEntities.values()) {
      for (TimelineEntity entity : entitySet) {
        entities.add(entity);
        ++entitiesAdded;
        if (entitiesAdded >= filters.getLimit()) {
          return entities;
        }
      }
    }
    return entities;
  }

  @Override
  public void serviceInit(Configuration conf) throws Exception {
    rootPath = conf.get(TIMELINE_SERVICE_STORAGE_DIR_ROOT,
        DEFAULT_TIMELINE_SERVICE_STORAGE_DIR_ROOT);
    super.serviceInit(conf);
  }

  @Override
  public TimelineEntity getEntity(TimelineReaderContext context,
      TimelineDataToRetrieve dataToRetrieve) throws IOException {
    String flowRunPath = getFlowRunPath(context.getUserId(),
        context.getClusterId(), context.getFlowName(), context.getFlowRunId(),
        context.getAppId());
    File dir = new File(new File(rootPath, ENTITIES_DIR),
        context.getClusterId() + "/" + flowRunPath + "/" + context.getAppId() +
        "/" + context.getEntityType());
    File entityFile = new File(
        dir, context.getEntityId() + TIMELINE_SERVICE_STORAGE_EXTENSION);
    try (BufferedReader reader =
             new BufferedReader(new InputStreamReader(
                 new FileInputStream(entityFile), Charset.forName("UTF-8")))) {
      TimelineEntity entity = readEntityFromFile(reader);
      return createEntityToBeReturned(
          entity, dataToRetrieve.getFieldsToRetrieve());
    } catch (FileNotFoundException e) {
      LOG.info("Cannot find entity {id:" + context.getEntityId() + " , type:" +
          context.getEntityType() + "}. Will send HTTP 404 in response.");
      return null;
    }
  }

  @Override
  public Set<TimelineEntity> getEntities(TimelineReaderContext context,
      TimelineEntityFilters filters, TimelineDataToRetrieve dataToRetrieve)
      throws IOException {
    String flowRunPath = getFlowRunPath(context.getUserId(),
        context.getClusterId(), context.getFlowName(), context.getFlowRunId(),
        context.getAppId());
    File dir =
        new File(new File(rootPath, ENTITIES_DIR),
            context.getClusterId() + "/" + flowRunPath + "/" +
            context.getAppId() + "/" + context.getEntityType());
    return getEntities(dir, context.getEntityType(), filters, dataToRetrieve);
  }
}