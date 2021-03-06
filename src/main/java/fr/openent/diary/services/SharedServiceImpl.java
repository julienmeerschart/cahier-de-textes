package fr.openent.diary.services;

import fr.wseduc.webutils.Either;
import org.entcore.common.neo4j.Neo4j;
import org.entcore.common.sql.*;
import org.entcore.common.utils.StringUtils;
import org.vertx.java.core.Handler;
import org.vertx.java.core.eventbus.Message;
import org.vertx.java.core.json.JsonArray;
import org.vertx.java.core.json.JsonObject;
import org.vertx.java.core.logging.Logger;
import org.vertx.java.core.logging.impl.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.entcore.common.sql.SqlResult.validUniqueResultHandler;

/**
 * Created by dbreyton on 13/07/2016.
 * TODO refactor in ENTCORE
 */
public class SharedServiceImpl implements SharedService {

    private final Neo4j neo = Neo4j.getInstance();
    private final Sql sql = Sql.getInstance();
    private final JsonArray profileNamesToShare = new JsonArray(new String[] {"Teacher","Student","Relative"});

    private final static Logger log = LoggerFactory.getLogger(SharedServiceImpl.class);
    private String schema;
    private String membersTable;
    private String shareTable;

    private String groupsTable;
    private String usersTable;

    public SharedServiceImpl(final String sqlConfContext) {
        final SqlConf sqlConf = SqlConfs.getInstance().get(sqlConfContext);
        this.schema = sqlConf.getSchema();
        this.membersTable = schema + "groups";
        this.groupsTable = schema + "groups";
        this.usersTable = schema + "users";
        this.shareTable = schema + ((sqlConf.getShareTable() != null && !sqlConf.getShareTable().trim().isEmpty()) ? sqlConf.getShareTable() : "shares");
    }

    @Override
    public void shareResource(String ownerId, String classGroupId, final String resourceId, Boolean isGroup, final List<String> actions,
                              final Handler<Either<String, JsonObject>> handler) {
        if (isGroup) {
            massGroupShare(resourceId, Arrays.asList(classGroupId), actions, handler);
        } else {
            findAllSubGroup(classGroupId, new Handler<Message<JsonObject>>() {
                @Override
                public void handle(Message<JsonObject> event) {
                    if ("ok".equals(event.body().getString("status"))) {
                        JsonArray r = event.body().getArray("result", new JsonArray());
                        //fixme TODO CHECK VISIBILITY IF NECESSARY
                        massGroupShare(resourceId, getListFromNeoResult(r), actions, handler);
                    } else {
                        handler.handle(new Either.Left<String, JsonObject>(event.body().getString("message")));
                    }
                }
            });
        }
    }

    private void massGroupShare(final String resourceId, final List<String> groupShareIds,
                                final List<String> actions, final Handler<Either<String, JsonObject>> handler) {
        final StringBuilder rawParams = new StringBuilder();
        for (final String groupShareId : groupShareIds) {
            rawParams.append("'").append(groupShareId).append("'").append(",");
        }
        rawParams.deleteCharAt(rawParams.length() - 1);

        final SqlStatementsBuilder s = new SqlStatementsBuilder();
        s.raw("LOCK TABLE " + membersTable + " IN SHARE ROW EXCLUSIVE MODE");
        s.raw("LOCK TABLE " + shareTable + " IN SHARE ROW EXCLUSIVE MODE");
        s.raw(
                "INSERT INTO " + membersTable + " (id) SELECT id " +
                        "FROM unnest(ARRAY[" + rawParams.toString() + "]) M (id) " +
                        "WHERE NOT EXISTS (SELECT * FROM " + membersTable + " WHERE id = M.id);"
        );

        final Object rId = Sql.parseId(resourceId);
        final String query =
                "INSERT INTO " + shareTable + " (member_id, resource_id, action) SELECT ?, ?, ? WHERE NOT EXISTS " +
                        "(SELECT * FROM " + shareTable + " WHERE member_id = ? AND resource_id = ? AND action = ?);";
        for (String action : actions) {
            for (String shareId : groupShareIds) {
                JsonArray ar = new JsonArray()
                        .add(shareId).add(rId).add(action).add(shareId).add(rId).add(action);
                s.prepared(query, ar);
            }
        }
        sql.transaction(s.build(), new Handler<Message<JsonObject>>() {
            @Override
            public void handle(Message<JsonObject> res) {
                Either<String, JsonObject> r = SqlResult.validUniqueResult(2, res);
                handler.handle(r);
            }
        });
    }

    private void findAllSubGroup(String classGroupId, final Handler<Message<JsonObject>> handler) {
        //find all subgroup of the classroom
        String query = "MATCH (:Class {id:{classId}})<-[:DEPENDS]-(g:ProfileGroup)-[:DEPENDS]->(ProfileGroup)-[:HAS_PROFILE]-(p:Profile) " +
                "WHERE p.name IN {profileNames} return g.id";
        final JsonObject params = new JsonObject().putString("classId", classGroupId).putArray("profileNames", profileNamesToShare);

        neo.execute(query, params, handler);
    }

    @Override
    public void updateShareResource(final String oldClassGroupId, final String newClassGroupId, final String resourceId, final Boolean isOldGroup, final Boolean isNewGroup,
                                    final List<String> actions, final Handler<Either<String, JsonObject>> handler) {
        if (!StringUtils.isEmpty(oldClassGroupId) && !StringUtils.isEmpty(newClassGroupId) && !oldClassGroupId.equals(newClassGroupId)) {
            if (isOldGroup && isNewGroup) {
                removeAndCreateShareResource(Arrays.asList(oldClassGroupId), Arrays.asList(newClassGroupId), resourceId, actions, handler);
            } else if ((isOldGroup && !isNewGroup) || (!isOldGroup && isNewGroup)) {
                findAllSubGroup((isOldGroup ? newClassGroupId : oldClassGroupId), new Handler<Message<JsonObject>>() {
                    @Override
                    public void handle(Message<JsonObject> event) {
                        if ("ok".equals(event.body().getString("status"))) {
                            final JsonArray result = event.body().getArray("result", new JsonArray());
                            final List<String> groupShareIds = getListFromNeoResult(result);
                            removeAndCreateShareResource((isOldGroup ? Arrays.asList(oldClassGroupId) : groupShareIds),
                                    (isNewGroup ? Arrays.asList(newClassGroupId) : groupShareIds), resourceId, actions, handler);
                        } else {
                            handler.handle(new Either.Left<String, JsonObject>(event.body().getString("message")));
                        }
                    }
                });
            } else {
                findAllSubGroup(oldClassGroupId, new Handler<Message<JsonObject>>() {
                    @Override
                    public void handle(Message<JsonObject> event) {
                        if ("ok".equals(event.body().getString("status"))) {
                            final JsonArray oldResult = event.body().getArray("result", new JsonArray());
                            final List<String> oldGroupShareIds = getListFromNeoResult(oldResult);
                            findAllSubGroup(newClassGroupId, new Handler<Message<JsonObject>>() {
                                @Override
                                public void handle(Message<JsonObject> event) {
                                    if ("ok".equals(event.body().getString("status"))) {
                                        final JsonArray newResult = event.body().getArray("result", new JsonArray());
                                        removeAndCreateShareResource(oldGroupShareIds, getListFromNeoResult(newResult), resourceId, actions, handler);
                                    } else {
                                        handler.handle(new Either.Left<String, JsonObject>(event.body().getString("message")));
                                    }
                                }
                            });
                        } else {
                            handler.handle(new Either.Left<String, JsonObject>(event.body().getString("message")));
                        }
                    }
                });
            }
        } else {
            //nothing TODO
            handler.handle(new Either.Right<String, JsonObject>(new JsonObject()));
        }
    }

    private void removeAndCreateShareResource(List<String> oldClassGroupId, final List<String> newClassGroupId, final String resourceId, final List<String> actions, final Handler<Either<String, JsonObject>> handler) {
        removeMassGroupShare(resourceId, oldClassGroupId, new Handler<Either<String, JsonObject>>() {
            @Override
            public void handle(Either<String, JsonObject> event) {
                if (event.isRight()) {
                    massGroupShare(resourceId, newClassGroupId, actions, handler);
                }
            }
        });
    }

    @Override
    public void removeShareResource(String classGroupId, final String resourceId, Boolean isGroup,
                                     final Handler<Either<String, JsonObject>> handler) {
        if (isGroup) {
            removeMassGroupShare(resourceId, Arrays.asList(classGroupId), handler);
        } else {
            findAllSubGroup(classGroupId, new Handler<Message<JsonObject>>() {
                @Override
                public void handle(Message<JsonObject> event) {
                    if ("ok".equals(event.body().getString("status"))) {
                        JsonArray r = event.body().getArray("result", new JsonArray());
                        removeMassGroupShare(resourceId, getListFromNeoResult(r), handler);
                    } else {
                        handler.handle(new Either.Left<String, JsonObject>(event.body().getString("message")));
                    }
                }
            });
        }
    }

    private void removeMassGroupShare(final String resourceId, final List<String> groupShareIds,
                                             final Handler<Either<String, JsonObject>> handler) {
        final Object[] g = groupShareIds.toArray();

        final String query = "DELETE FROM " + shareTable + " WHERE member_id IN " + Sql.listPrepared(g) + " AND resource_id = ? ";
        final JsonArray values = new JsonArray(g);
        values.add(Sql.parseId(resourceId));
        sql.prepared(query, values, SqlResult.validUniqueResultHandler(handler));
    }

    private List<String> getListFromNeoResult(JsonArray r) {
        final List<String> groupShareIds = new ArrayList<String>();
        for (int i = 0; i < r.size(); i++) {
            JsonObject j = r.get(i);
            if (j != null && j.getString("g.id") != null) {
                groupShareIds.add(j.getString("g.id"));
            }
        }
        return groupShareIds;
    }

    @Override
    public void shareOrUpdateLinkedResources(final List<Long> resourceIds, final String userId, final String groupId, final List<String> actions,
                                            final Handler<Either<String, JsonObject>> handler) {

        final boolean isGroup;
        final String memberId;

        if (groupId == null) {
            memberId = userId;
            isGroup = false;
        } else {
            memberId = groupId;
            isGroup = true;
        }

        removeLinkedHomeworks(resourceIds, memberId, actions, new Handler<Either<String, JsonObject>>() {
            @Override
            public void handle(Either<String, JsonObject> event) {
                if (event.isRight()) {
                    shareLinkedHomeworks(resourceIds, memberId, isGroup, actions, handler);
                } else {
                    handler.handle(new Either.Left<String, JsonObject>(event.left().getValue()));
                }
            }
        });
    }

    @Override
    public void removeLinkedHomeworks(final List<Long> homeworkIds, final String memberId, List<String> actions,
                                      final Handler<Either<String, JsonObject>> handler) {

        String actionFilter;
        JsonArray parameters;
        if (actions != null && actions.size() > 0) {
            Object[] a = actions.toArray();
            actionFilter = "action IN " + Sql.listPrepared(a) + " AND ";
            parameters = new JsonArray(a);
        } else {
            actionFilter = "";
            parameters = new JsonArray();
        }

        for (Long homeworkId: homeworkIds) {
            parameters.addNumber(homeworkId);
        }

        StringBuilder query = new StringBuilder();
        query.append("DELETE FROM diary.homework_shares WHERE ");
        query.append(actionFilter);
        query.append("resource_id IN ");
        query.append(Sql.listPrepared(homeworkIds.toArray()));
        query.append(" AND member_id = ? ");

        parameters.add(memberId);

        sql.prepared(query.toString(), parameters, validUniqueResultHandler(handler));
    }

    private void shareLinkedHomeworks(final List<Long> resourceIds, final String memberId, final boolean isGroup, final List<String> actions,
                                      final Handler<Either<String, JsonObject>> handler) {

        String membersTable = (isGroup) ? this.groupsTable : this.usersTable;

        final SqlStatementsBuilder s = new SqlStatementsBuilder();
        s.raw("LOCK TABLE " + membersTable + " IN SHARE ROW EXCLUSIVE MODE");
        s.raw("LOCK TABLE diary.homework_shares IN SHARE ROW EXCLUSIVE MODE");

        StringBuilder raw = new StringBuilder();
        raw.append("INSERT INTO ").append(membersTable).append("(id) SELECT '").append(memberId).append("'");
        raw.append(" WHERE NOT EXISTS (SELECT * FROM ").append(membersTable).append(" WHERE id = '").append(memberId).append("');");

        s.raw(raw.toString());

        final StringBuilder query = new StringBuilder();
        query.append("INSERT INTO diary.homework_shares (member_id, resource_id, action) SELECT ?, ?, ? WHERE NOT EXISTS ");
        query.append("(SELECT * FROM diary.homework_shares WHERE member_id = ? AND resource_id = ? AND action = ?);");

        for (String action : actions) {
            for (Long resourceId: resourceIds) {
                JsonArray queryParams = new JsonArray();
                queryParams.add(memberId).add(resourceId).add(action).add(memberId).add(resourceId).add(action);
                s.prepared(query.toString(), queryParams);
            }
        }

        sql.transaction(s.build(), new Handler<Message<JsonObject>>() {
            @Override
            public void handle(Message<JsonObject> res) {
                Either<String, JsonObject> r = SqlResult.validUniqueResult(2, res);
                handler.handle(r);
            }
        });
    }
}