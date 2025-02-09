package org.hypertrace.entity.data.service;

import static java.util.Objects.isNull;
import static org.hypertrace.entity.service.constants.EntityCollectionConstants.ENRICHED_ENTITIES_COLLECTION;
import static org.hypertrace.entity.service.constants.EntityCollectionConstants.ENTITY_RELATIONSHIPS_COLLECTION;
import static org.hypertrace.entity.service.constants.EntityCollectionConstants.RAW_ENTITIES_COLLECTION;

import com.google.common.collect.Streams;
import com.google.protobuf.Descriptors;
import com.google.protobuf.GeneratedMessageV3;
import com.google.protobuf.Message;
import com.google.protobuf.ServiceException;
import io.grpc.Channel;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.hypertrace.core.documentstore.Collection;
import org.hypertrace.core.documentstore.Datastore;
import org.hypertrace.core.documentstore.Document;
import org.hypertrace.core.documentstore.Filter;
import org.hypertrace.core.documentstore.JSONDocument;
import org.hypertrace.core.documentstore.Key;
import org.hypertrace.core.grpcutils.context.RequestContext;
import org.hypertrace.entity.data.service.v1.ByIdRequest;
import org.hypertrace.entity.data.service.v1.ByTypeAndIdentifyingAttributes;
import org.hypertrace.entity.data.service.v1.Empty;
import org.hypertrace.entity.data.service.v1.EnrichedEntities;
import org.hypertrace.entity.data.service.v1.EnrichedEntity;
import org.hypertrace.entity.data.service.v1.Entities;
import org.hypertrace.entity.data.service.v1.Entity;
import org.hypertrace.entity.data.service.v1.Entity.Builder;
import org.hypertrace.entity.data.service.v1.EntityDataServiceGrpc.EntityDataServiceImplBase;
import org.hypertrace.entity.data.service.v1.EntityRelationship;
import org.hypertrace.entity.data.service.v1.EntityRelationships;
import org.hypertrace.entity.data.service.v1.MergeAndUpsertEntityRequest;
import org.hypertrace.entity.data.service.v1.MergeAndUpsertEntityResponse;
import org.hypertrace.entity.data.service.v1.Query;
import org.hypertrace.entity.data.service.v1.RelationshipsQuery;
import org.hypertrace.entity.service.change.event.api.EntityChangeEventGenerator;
import org.hypertrace.entity.service.constants.EntityServiceConstants;
import org.hypertrace.entity.service.exception.InvalidRequestException;
import org.hypertrace.entity.service.util.DocStoreConverter;
import org.hypertrace.entity.service.util.DocStoreJsonFormat;
import org.hypertrace.entity.service.util.StringUtils;
import org.hypertrace.entity.type.service.rxclient.EntityTypeClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * EntityDataService implementation with CRUD and query operations for entities on the doc store.
 */
public class EntityDataServiceImpl extends EntityDataServiceImplBase {
  private static final Logger LOG = LoggerFactory.getLogger(EntityDataServiceImpl.class);
  private static final DocumentParser PARSER = new DocumentParser();
  private static final DocStoreJsonFormat.Printer PRINTER = DocStoreJsonFormat.printer();
  private static final String EVENT_STORE = "event.store";
  private static final String EVENT_STORE_TYPE_CONFIG = "type";
  private static final String ENTITY_CHANGE_EVENTS_TOPIC = "entity-change-events";
  private static final String ENTITY_CHANGE_EVENTS_PRODUCER_CONFIG =
      "entity.change.events.producer";

  private final Collection entitiesCollection;
  private final Collection relationshipsCollection;
  private final Collection enrichedEntitiesCollection;
  private final EntityNormalizer entityNormalizer;
  private final UpsertConditionMatcher upsertConditionMatcher = new UpsertConditionMatcher();
  private final EntityIdGenerator entityIdGenerator;
  private final EntityChangeEventGenerator entityChangeEventGenerator;

  public EntityDataServiceImpl(
      Datastore datastore,
      Channel entityTypeChannel,
      EntityChangeEventGenerator entityChangeEventGenerator) {
    this.entitiesCollection = datastore.getCollection(RAW_ENTITIES_COLLECTION);
    this.relationshipsCollection = datastore.getCollection(ENTITY_RELATIONSHIPS_COLLECTION);
    this.enrichedEntitiesCollection = datastore.getCollection(ENRICHED_ENTITIES_COLLECTION);

    this.entityIdGenerator = new EntityIdGenerator();
    EntityTypeClient entityTypeClient = EntityTypeClient.builder(entityTypeChannel).build();
    IdentifyingAttributeCache identifyingAttributeCache = new IdentifyingAttributeCache(datastore);
    this.entityNormalizer =
        new EntityNormalizer(entityTypeClient, this.entityIdGenerator, identifyingAttributeCache);
    this.entityChangeEventGenerator = entityChangeEventGenerator;
  }

  /**
   * Creates or Updates an Entity <br>
   * If the entityId is provided it is used as is to update the Entity. If the identifying
   * attributes are provided, then the entityId is generated from them If none of the above are
   * provided, we error out. The ID of the entity is generated from its identifying attributes.
   *
   * @param request Entity to be created
   * @param responseObserver Observer to be notified on about the Entity creation request
   */
  @Override
  public void upsert(Entity request, StreamObserver<Entity> responseObserver) {
    String tenantId = RequestContext.CURRENT.get().getTenantId().orElse(null);
    if (tenantId == null) {
      responseObserver.onError(new ServiceException("Tenant id is missing in the request."));
      return;
    }

    try {
      Entity normalizedEntity = this.entityNormalizer.normalize(tenantId, request);
      java.util.Collection<Entity> existingEntityCollection =
          getExistingEntities(List.of(normalizedEntity));
      upsertEntity(
          tenantId,
          normalizedEntity.getEntityId(),
          normalizedEntity.getEntityType(),
          normalizedEntity,
          Entity.newBuilder(),
          entitiesCollection,
          responseObserver);
      entityChangeEventGenerator.sendChangeNotification(
          RequestContext.CURRENT.get(), existingEntityCollection, List.of(normalizedEntity));
    } catch (Throwable throwable) {
      LOG.warn("Failed to upsert: {}", request, throwable);
      responseObserver.onError(throwable);
    }
  }

  @Override
  public void upsertEntities(Entities request, StreamObserver<Empty> responseObserver) {
    String tenantId = RequestContext.CURRENT.get().getTenantId().orElse(null);
    if (tenantId == null) {
      responseObserver.onError(new ServiceException("Tenant id is missing in the request."));
      return;
    }

    try {
      Map<Key, Entity> entities =
          request.getEntityList().stream()
              .map(entity -> this.entityNormalizer.normalize(tenantId, entity))
              .collect(
                  Collectors.toUnmodifiableMap(
                      entity -> this.entityNormalizer.getEntityDocKey(tenantId, entity),
                      Function.identity()));
      java.util.Collection<Entity> existingEntities = getExistingEntities(entities.values());
      upsertEntities(entities, entitiesCollection, responseObserver);
      entityChangeEventGenerator.sendChangeNotification(
          RequestContext.CURRENT.get(), existingEntities, entities.values());
    } catch (Throwable throwable) {
      LOG.warn("Failed to upsert: {}", request, throwable);
      responseObserver.onError(throwable);
    }
  }

  @Override
  public void getAndUpsertEntities(Entities request, StreamObserver<Entity> responseObserver) {
    String tenantId = RequestContext.CURRENT.get().getTenantId().orElse(null);
    if (tenantId == null) {
      responseObserver.onError(new ServiceException("Tenant id is missing in the request."));
      return;
    }

    try {

      Map<Key, Document> documentMap = new HashMap<>();
      List<Entity> updatedEntities = new ArrayList<>();
      for (Entity entity : request.getEntityList()) {
        Entity normalizedEntity = this.entityNormalizer.normalize(tenantId, entity);
        updatedEntities.add(normalizedEntity);
        Document doc = convertEntityToDocument(normalizedEntity);
        Key key = this.entityNormalizer.getEntityDocKey(tenantId, normalizedEntity);
        documentMap.put(key, doc);
      }

      List<Entity> existingEntities =
          Streams.stream(entitiesCollection.bulkUpsertAndReturnOlderDocuments(documentMap))
              .flatMap(
                  document -> PARSER.<Entity>parseOrLog(document, Entity.newBuilder()).stream())
              .map(Entity::toBuilder)
              .map(builder -> builder.setTenantId(tenantId))
              .map(Entity.Builder::build)
              .collect(Collectors.toList());

      existingEntities.forEach(responseObserver::onNext);
      responseObserver.onCompleted();

      entityChangeEventGenerator.sendChangeNotification(
          RequestContext.CURRENT.get(), existingEntities, updatedEntities);
    } catch (IOException e) {
      LOG.error("Failed to bulk upsert entities", e);
      responseObserver.onError(e);
    }
  }

  /**
   * Get an Entity by the EntityId and EntityType
   *
   * @param request ID of the entity which constitutes the EntityType and EntityID(UUID)
   * @param responseObserver Observer to be notified on about the Entity get request
   */
  @Override
  public void getById(ByIdRequest request, StreamObserver<Entity> responseObserver) {
    try {
      validate(request);
    } catch (InvalidRequestException e) {
      responseObserver.onError(e);
      return;
    }

    Optional<String> tenantId = RequestContext.CURRENT.get().getTenantId();
    if (tenantId.isEmpty()) {
      responseObserver.onError(new ServiceException("Tenant id is missing in the request."));
      return;
    }

    searchByIdAndStreamSingleResponse(
        tenantId.get(),
        request.getEntityId(),
        request.getEntityType(),
        entitiesCollection,
        Entity.newBuilder(),
        responseObserver);
  }

  /**
   * Get an Entity by the EntityType and its identifying attributes
   *
   * @param request ID of the entity which constitutes the EntityType and EntityID(UUID)
   * @param responseObserver Observer to be notified on about the Entity get request
   */
  @Override
  public void getByTypeAndIdentifyingProperties(
      ByTypeAndIdentifyingAttributes request, StreamObserver<Entity> responseObserver) {
    try {
      validate(request);
    } catch (InvalidRequestException e) {
      responseObserver.onError(e);
      return;
    }

    String tenantId = RequestContext.CURRENT.get().getTenantId().orElse(null);
    if (tenantId == null) {
      responseObserver.onError(new ServiceException("Tenant id is missing in the request."));
      return;
    }

    String entityId =
        this.entityIdGenerator.generateEntityId(
            tenantId, request.getEntityType(), request.getIdentifyingAttributesMap());
    searchByIdAndStreamSingleResponse(
        tenantId,
        entityId,
        request.getEntityType(),
        entitiesCollection,
        Entity.newBuilder(),
        responseObserver);
  }

  /**
   * Deletes an Entity by the EntityId and EntityType
   *
   * @param request ID of the entity to be deleted
   * @param responseObserver Observer to be notified on about the Entity delete request
   */
  @Override
  public void delete(ByIdRequest request, StreamObserver<Empty> responseObserver) {
    if (StringUtils.isEmpty(request.getEntityId())) {
      LOG.info("{}. Invalid delete request:{}", request, ErrorMessages.ENTITY_ID_EMPTY);
      responseObserver.onError(new RuntimeException(ErrorMessages.ENTITY_ID_EMPTY));
      return;
    }

    Optional<String> tenantId = RequestContext.CURRENT.get().getTenantId();
    if (tenantId.isEmpty()) {
      responseObserver.onError(new ServiceException("Tenant id is missing in the request."));
      return;
    }

    Optional<Entity> existingEntity =
        getExistingEntity(tenantId.get(), request.getEntityType(), request.getEntityId());
    Key key =
        this.entityNormalizer.getEntityDocKey(
            tenantId.get(), request.getEntityType(), request.getEntityId());
    if (entitiesCollection.delete(key)) {
      responseObserver.onNext(Empty.newBuilder().build());
      responseObserver.onCompleted();
      existingEntity.ifPresent(
          entity ->
              entityChangeEventGenerator.sendDeleteNotification(
                  RequestContext.CURRENT.get(), List.of(entity)));
    } else {
      responseObserver.onError(new RuntimeException("Could not delete the entity."));
    }
  }

  /**
   * Fetch entities by applying filters
   *
   * @param request Query filters to be applied for filtering entities
   * @param responseObserver Observer to be notified on about the Entity query request
   */
  @Override
  public void query(Query request, StreamObserver<Entity> responseObserver) {
    logQuery(request);
    Optional<String> tenantId = RequestContext.CURRENT.get().getTenantId();
    if (tenantId.isEmpty()) {
      responseObserver.onError(new ServiceException("Tenant id is missing in the request."));
      return;
    }

    this.doQuery(DocStoreConverter.transform(tenantId.get(), request, Collections.emptyList()))
        .forEach(responseObserver::onNext);

    responseObserver.onCompleted();
  }

  @Override
  public void upsertRelationships(
      EntityRelationships request, StreamObserver<Empty> responseObserver) {
    String tenantId = RequestContext.CURRENT.get().getTenantId().orElse(null);
    if (tenantId == null) {
      responseObserver.onError(new ServiceException("Tenant id is missing in the request."));
      return;
    }

    Map<Key, Document> entityRelations = new HashMap<>();
    try {

      for (EntityRelationship relationship : request.getRelationshipList()) {
        if (StringUtils.isEmpty(relationship.getFromEntityId())
            || StringUtils.isEmpty(relationship.getToEntityId())
            || StringUtils.isEmpty(relationship.getEntityRelationshipType())) {
          LOG.warn("Invalid relationship upsert request:{}", relationship);
          continue;
        }

        Document document = convertEntityRelationshipToDocument(tenantId, relationship);
        Key key =
            new EntityRelationshipDocKey(
                tenantId,
                relationship.getEntityRelationshipType(),
                relationship.getFromEntityId(),
                relationship.getToEntityId());

        entityRelations.put(key, document);
      }

      boolean status = relationshipsCollection.bulkUpsert(entityRelations);
      if (status) {
        responseObserver.onNext(Empty.newBuilder().build());
        responseObserver.onCompleted();
      } else {
        responseObserver.onError(new RuntimeException("Could not bulk upsert relationships."));
      }
    } catch (IOException e) {
      LOG.error("Failed to bulk upsert relationships.", e);
      responseObserver.onError(e);
    }
  }

  @Override
  public void getRelationships(
      RelationshipsQuery query, StreamObserver<EntityRelationship> responseObserver) {
    logQuery(query);

    Optional<String> tenantId = RequestContext.CURRENT.get().getTenantId();
    if (tenantId.isEmpty()) {
      responseObserver.onError(new ServiceException("Tenant id is missing in the request."));
      return;
    }

    org.hypertrace.core.documentstore.Query docStoreQuery =
        new org.hypertrace.core.documentstore.Query();
    List<Filter> filters = new ArrayList<>();
    filters.add(DocStoreConverter.getTenantIdEqFilter(tenantId.get()));
    if (query.getEntityRelationshipCount() > 0) {
      filters.add(
          new Filter(
              Filter.Op.IN,
              EntityServiceConstants.ENTITY_RELATIONSHIP_TYPE,
              query.getEntityRelationshipList()));
    }
    if (query.getFromEntityIdCount() > 0) {
      filters.add(
          new Filter(
              Filter.Op.IN, EntityServiceConstants.FROM_ENTITY_ID, query.getFromEntityIdList()));
    }
    if (query.getToEntityIdCount() > 0) {
      filters.add(
          new Filter(Filter.Op.IN, EntityServiceConstants.TO_ENTITY_ID, query.getToEntityIdList()));
    }

    if (!filters.isEmpty()) {
      if (filters.size() == 1) {
        docStoreQuery.setFilter(filters.get(0));
      } else {
        Filter f = new Filter();
        f.setOp(Filter.Op.AND);
        f.setChildFilters(filters.toArray(new Filter[] {}));
        docStoreQuery.setFilter(f);
      }
    }

    searchByQueryAndStreamRelationships(docStoreQuery, responseObserver, tenantId.get());
  }

  @Override
  public void upsertEnrichedEntity(
      EnrichedEntity request, StreamObserver<EnrichedEntity> responseObserver) {
    Optional<String> tenantId = RequestContext.CURRENT.get().getTenantId();
    if (tenantId.isEmpty()) {
      responseObserver.onError(new ServiceException("Tenant id is missing in the request."));
      return;
    }

    if (StringUtils.isEmpty(request.getEntityType())) {
      LOG.info(
          "{}. Invalid upsertEnrichedEntity request:{}", request, ErrorMessages.ENTITY_TYPE_EMPTY);
      responseObserver.onError(new RuntimeException(ErrorMessages.ENTITY_TYPE_EMPTY));
      return;
    }

    if (StringUtils.isEmpty(request.getEntityId())) {
      LOG.info(
          "{}. Invalid upsertEnrichedEntity request:{}", request, ErrorMessages.ENTITY_ID_EMPTY);
      responseObserver.onError(new RuntimeException(ErrorMessages.ENTITY_ID_EMPTY));
      return;
    }

    upsertEntity(
        tenantId.get(),
        request.getEntityId(),
        request.getEntityType(),
        request,
        EnrichedEntity.newBuilder(),
        enrichedEntitiesCollection,
        responseObserver);
  }

  @Override
  public void upsertEnrichedEntities(
      EnrichedEntities request, StreamObserver<Empty> responseObserver) {
    String tenantId = RequestContext.CURRENT.get().getTenantId().orElse(null);
    if (isNull(tenantId)) {
      responseObserver.onError(new ServiceException("Tenant id is missing in the request."));
      return;
    }

    for (EnrichedEntity entity : request.getEntitiesList()) {
      if (StringUtils.isEmpty(entity.getEntityType())) {
        LOG.info(
            "{}. Invalid upsertEnrichedEntities request:{}",
            entity,
            ErrorMessages.ENTITY_TYPE_EMPTY);
        responseObserver.onError(new RuntimeException(ErrorMessages.ENTITY_TYPE_EMPTY));
        return;
      }

      if (StringUtils.isEmpty(entity.getEntityId())) {
        LOG.info(
            "{}. Invalid upsertEnrichedEntities request:{}", entity, ErrorMessages.ENTITY_ID_EMPTY);
        responseObserver.onError(new RuntimeException(ErrorMessages.ENTITY_ID_EMPTY));
        return;
      }
    }

    Map<Key, EnrichedEntity> entities =
        request.getEntitiesList().stream()
            .collect(
                Collectors.toUnmodifiableMap(
                    entity ->
                        this.entityNormalizer.getEntityDocKey(
                            tenantId, entity.getEntityType(), entity.getEntityId()),
                    Function.identity()));

    upsertEntities(entities, enrichedEntitiesCollection, responseObserver);
  }

  @Override
  public void getEnrichedEntityById(
      ByIdRequest request, StreamObserver<EnrichedEntity> responseObserver) {
    try {
      validate(request);
    } catch (InvalidRequestException e) {
      responseObserver.onError(e);
      return;
    }

    Optional<String> tenantId = RequestContext.CURRENT.get().getTenantId();
    if (tenantId.isEmpty()) {
      responseObserver.onError(new ServiceException("Tenant id is missing in the request."));
      return;
    }

    searchByIdAndStreamSingleResponse(
        tenantId.get(),
        request.getEntityId(),
        request.getEntityType(),
        enrichedEntitiesCollection,
        EnrichedEntity.newBuilder(),
        responseObserver);
  }

  @Override
  public void getEnrichedEntityByTypeAndIdentifyingProps(
      ByTypeAndIdentifyingAttributes request, StreamObserver<EnrichedEntity> responseObserver) {
    try {
      validate(request);
    } catch (InvalidRequestException e) {
      responseObserver.onError(e);
      return;
    }

    String tenantId = RequestContext.CURRENT.get().getTenantId().orElse(null);
    if (tenantId == null) {
      responseObserver.onError(new ServiceException("Tenant id is missing in the request."));
      return;
    }

    String entityId =
        this.entityIdGenerator.generateEntityId(
            tenantId, request.getEntityType(), request.getIdentifyingAttributesMap());
    searchByIdAndStreamSingleResponse(
        tenantId,
        entityId,
        request.getEntityType(),
        enrichedEntitiesCollection,
        EnrichedEntity.newBuilder(),
        responseObserver);
  }

  @Override
  public void mergeAndUpsertEntity(
      MergeAndUpsertEntityRequest request,
      StreamObserver<MergeAndUpsertEntityResponse> responseObserver) {
    RequestContext requestContext = RequestContext.CURRENT.get();
    String tenantId = requestContext.getTenantId().orElse(null);
    if (tenantId == null) {
      responseObserver.onError(new ServiceException("Tenant id is missing in the request."));
      return;
    }

    Entity receivedEntity = this.entityNormalizer.normalize(tenantId, request.getEntity());
    Optional<Entity> existingEntity =
        getExistingEntity(tenantId, receivedEntity.getEntityType(), receivedEntity.getEntityId());

    boolean rejectUpsertForConditionMismatch =
        existingEntity
            .map(
                entity ->
                    !this.upsertConditionMatcher.matches(entity, request.getUpsertCondition()))
            .orElse(false);

    if (rejectUpsertForConditionMismatch) {
      // There's an existing entity and the update doesn't meet the condition, return existing
      responseObserver.onNext(
          MergeAndUpsertEntityResponse.newBuilder().setEntity(existingEntity.get()).build());
      responseObserver.onCompleted();
    } else {
      // There's either a new entity or a valid update to upsert
      Entity entityToUpsert =
          existingEntity
              .map(Entity::toBuilder)
              .map(Entity.Builder::clearCreatedTime)
              .map(builder -> builder.mergeFrom(receivedEntity))
              .map(Builder::build)
              .orElse(receivedEntity);
      try {
        Entity upsertedEntity = this.upsertEntity(tenantId, entityToUpsert);
        responseObserver.onNext(
            MergeAndUpsertEntityResponse.newBuilder().setEntity(upsertedEntity).build());
        responseObserver.onCompleted();
        entityChangeEventGenerator.sendChangeNotification(
            requestContext,
            existingEntity.map(List::of).orElse(Collections.emptyList()),
            List.of(upsertedEntity));
      } catch (IOException e) {
        responseObserver.onError(e);
      }
    }
  }

  private void validate(ByIdRequest request) throws InvalidRequestException {
    if (StringUtils.isEmpty(request.getEntityId())) {
      LOG.info("{}. Invalid get request:{}", request, ErrorMessages.ENTITY_ID_EMPTY);
      throw new InvalidRequestException(ErrorMessages.ENTITY_ID_EMPTY);
    }
  }

  private void validate(ByTypeAndIdentifyingAttributes request) throws InvalidRequestException {
    if (StringUtils.isEmpty(request.getEntityType())) {
      LOG.info(
          "{}. Invalid ByTypeAndIdentifyingAttributes request: {}",
          request,
          ErrorMessages.ENTITY_TYPE_EMPTY);
      throw new InvalidRequestException(ErrorMessages.ENTITY_TYPE_EMPTY);
    }

    if (request.getIdentifyingAttributesMap().isEmpty()) {
      LOG.info(
          "{}. Invalid ByTypeAndIdentifyingAttributes request: {}",
          request,
          ErrorMessages.ENTITY_IDENTIFYING_ATTRS_EMPTY);
      throw new InvalidRequestException(ErrorMessages.ENTITY_IDENTIFYING_ATTRS_EMPTY);
    }
  }

  private <T extends GeneratedMessageV3> void upsertEntity(
      String tenantId,
      String entityId,
      String entityType,
      T entity,
      Message.Builder builder,
      Collection collection,
      StreamObserver<T> responseObserver) {
    try {
      Document document = convertEntityToDocument(entity);
      collection.upsertAndReturn(
          this.entityNormalizer.getEntityDocKey(tenantId, entityType, entityId), document);
      searchByIdAndStreamSingleResponse(
          tenantId, entityId, entityType, collection, builder, responseObserver);
    } catch (IOException e) {
      responseObserver.onError(new RuntimeException("Could not create entity.", e));
    }
  }

  private Entity upsertEntity(String tenantId, Entity entity) throws IOException {
    Document document = convertEntityToDocument(entity);
    Document result =
        entitiesCollection.upsertAndReturn(
            this.entityNormalizer.getEntityDocKey(tenantId, entity), document);
    return this.entityFromDocument(result).orElseThrow();
  }

  private <T extends GeneratedMessageV3> void upsertEntities(
      Map<Key, T> map, Collection collection, StreamObserver<Empty> responseObserver) {
    try {
      Map<Key, Document> entities = new HashMap<>();
      for (Map.Entry<Key, T> entry : map.entrySet()) {
        entities.put(entry.getKey(), convertEntityToDocument(entry.getValue()));
      }

      boolean status = collection.bulkUpsert(entities);
      if (status) {
        responseObserver.onNext(Empty.newBuilder().build());
        responseObserver.onCompleted();
      } else {
        responseObserver.onError(new RuntimeException("Failed to bulk upsert entities"));
      }
    } catch (IOException e) {
      LOG.error("Failed to bulk upsert entities", e);
      responseObserver.onError(e);
    }
  }

  private <T extends GeneratedMessageV3> JSONDocument convertEntityToDocument(T entity)
      throws IOException {
    try {
      return DocStoreConverter.transform(entity);
    } catch (IOException e) {
      LOG.error("Could not covert the attributes into JSON doc.", e);
      throw e;
    }
  }

  private JSONDocument convertEntityRelationshipToDocument(
      String tenantId, EntityRelationship relationship) throws IOException {
    try {
      String json =
          PRINTER.print(EntityRelationship.newBuilder(relationship).setTenantId(tenantId).build());
      return new JSONDocument(json);
    } catch (IOException e) {
      LOG.error("Could not covert the EntityRelationship into JSON doc.", e);
      throw e;
    }
  }

  private <T extends Message> void searchByIdAndStreamSingleResponse(
      String tenantId,
      String entityId,
      String entityType,
      Collection collection,
      Message.Builder builder,
      StreamObserver<T> responseObserver) {
    org.hypertrace.core.documentstore.Query query = new org.hypertrace.core.documentstore.Query();
    String docId = this.entityNormalizer.getEntityDocKey(tenantId, entityType, entityId).toString();
    query.setFilter(new Filter(Filter.Op.EQ, EntityServiceConstants.ID, docId));

    Iterator<Document> result = collection.search(query);
    List<T> entities = new ArrayList<>();
    while (result.hasNext()) {
      PARSER
          .<T>parseOrLog(result.next(), builder.clone())
          .map(
              entity -> {
                // Populate the tenant id field with the tenant id that's received for backward
                // compatibility.
                Descriptors.FieldDescriptor fieldDescriptor =
                    entity.getDescriptorForType().findFieldByName("tenant_id");
                if (fieldDescriptor != null) {
                  return (T) entity.toBuilder().setField(fieldDescriptor, tenantId).build();
                }
                return entity;
              })
          .ifPresent(entities::add);
    }

    if (LOG.isDebugEnabled()) {
      LOG.debug("Docstore query has returned the result: {}", entities);
    }

    if (entities.size() == 1) {
      responseObserver.onNext(entities.get(0));
      responseObserver.onCompleted();
    } else if (entities.size() > 1) {
      responseObserver.onError(
          new IllegalStateException("Multiple entities with same id are found."));
    } else {
      // When there is no result, we should return the default instance, which is a way
      // of saying it's null.
      // TODO : Not convinced with the default instance
      responseObserver.onNext((T) builder.build());
      responseObserver.onCompleted();
    }
  }

  private List<String> getEntityIds(java.util.Collection<Entity> entities) {
    return entities.stream().map(Entity::getEntityId).collect(Collectors.toList());
  }

  private List<Entity> getExistingEntities(java.util.Collection<Entity> entities) {
    List<String> docIds = entities.stream().map(this::getDocId).collect(Collectors.toList());
    return doQuery(buildExistingEntitiesInQuery(docIds)).collect(Collectors.toList());
  }

  private org.hypertrace.core.documentstore.Query buildExistingEntitiesInQuery(
      java.util.Collection<String> docIds) {
    org.hypertrace.core.documentstore.Query query = new org.hypertrace.core.documentstore.Query();
    query.setFilter(new Filter(Filter.Op.IN, EntityServiceConstants.ID, docIds));
    return query;
  }

  private String getDocId(Entity entity) {
    return this.entityNormalizer
        .getEntityDocKey(entity.getTenantId(), entity.getEntityType(), entity.getEntityId())
        .toString();
  }

  private Optional<Entity> getExistingEntity(String tenantId, String entityType, String entityId) {
    return this.doQuery(this.buildExistingEntityQuery(tenantId, entityType, entityId)).findFirst();
  }

  private org.hypertrace.core.documentstore.Query buildExistingEntityQuery(
      String tenantId, String entityType, String entityId) {
    org.hypertrace.core.documentstore.Query query = new org.hypertrace.core.documentstore.Query();
    String docId = this.entityNormalizer.getEntityDocKey(tenantId, entityType, entityId).toString();
    query.setFilter(new Filter(Filter.Op.EQ, EntityServiceConstants.ID, docId));
    return query;
  }

  private Stream<Entity> doQuery(org.hypertrace.core.documentstore.Query query) {
    return Streams.stream(entitiesCollection.search(query))
        .map(this::entityFromDocument)
        .flatMap(Optional::stream)
        .map(Entity::toBuilder)
        .map(Entity.Builder::build);
  }

  private Optional<Entity> entityFromDocument(Document document) {
    return PARSER.parseOrLog(document, Entity.newBuilder());
  }

  private void searchByQueryAndStreamRelationships(
      org.hypertrace.core.documentstore.Query query,
      StreamObserver<EntityRelationship> responseObserver,
      String tenantId) {
    List<EntityRelationship> relationships =
        Streams.stream(relationshipsCollection.search(query))
            .flatMap(
                document ->
                    PARSER
                        .<EntityRelationship>parseOrLog(document, EntityRelationship.newBuilder())
                        .stream())
            .map(EntityRelationship::toBuilder)
            .map(builder -> builder.setTenantId(tenantId))
            .map(EntityRelationship.Builder::build)
            .peek(responseObserver::onNext)
            .collect(Collectors.toList());

    if (LOG.isDebugEnabled()) {
      LOG.debug("Docstore query has returned the result: {}", relationships);
    }
    responseObserver.onCompleted();
  }

  private void logQuery(Object query) {
    if (LOG.isDebugEnabled()) {
      LOG.info("Received query: {}", query.toString());
    }
  }

  static class ErrorMessages {
    static final String ENTITY_ID_EMPTY = "Entity ID is empty";
    static final String ENTITY_TYPE_EMPTY = "Entity Type is empty";
    static final String ENTITY_IDENTIFYING_ATTRS_EMPTY = "Entity identifying attributes are empty";
  }
}
