package com.lastsector.storage;
import java.sql.*;
/** V1 DDL is restartable on MySQL (DDL auto-commits); SQLite rolls DDL back transactionally. */
public final class SchemaMigrations {
    public static final int VERSION=2;
    private SchemaMigrations() {}
    public static int migrate(Connection c,String type)throws SQLException {
        boolean mysql=type.equals("mysql");c.setAutoCommit(false);
        try(var s=c.createStatement()) {
            s.executeUpdate("CREATE TABLE IF NOT EXISTS lastsector_schema (id INTEGER PRIMARY KEY, version INTEGER NOT NULL)");
            s.executeUpdate((mysql?"INSERT IGNORE":"INSERT OR IGNORE")+" INTO lastsector_schema(id,version) VALUES(1,0)");
            int version;try(var r=s.executeQuery("SELECT version FROM lastsector_schema WHERE id=1")){if(!r.next())throw new SQLException("Missing schema version");version=r.getInt(1);}
            if(version<0 || version>VERSION)throw new SQLException("Unsupported recovery schema version");
            if(version==0) {
                String text=mysql?"LONGTEXT":"TEXT";
                s.executeUpdate("CREATE TABLE IF NOT EXISTS recovery_sessions (session_id VARCHAR(36) PRIMARY KEY, room_id VARCHAR(128) NOT NULL, map_id VARCHAR(128) NOT NULL, world_name VARCHAR(512) NOT NULL, game_state VARCHAR(24) NOT NULL, revision BIGINT NOT NULL, format_version INTEGER NOT NULL, payload "+text+" NOT NULL, checksum VARCHAR(64) NOT NULL, updated_at BIGINT NOT NULL, status VARCHAR(24) NOT NULL, owner_token VARCHAR(36), lease_until BIGINT NOT NULL)");
                s.executeUpdate("CREATE TABLE IF NOT EXISTS pending_player_restores (player_uuid VARCHAR(36) PRIMARY KEY, session_id VARCHAR(36) NOT NULL, generation VARCHAR(36) NOT NULL, format_version INTEGER NOT NULL, payload "+text+" NOT NULL, checksum VARCHAR(64) NOT NULL, status VARCHAR(24) NOT NULL, created_at BIGINT NOT NULL)");
                s.executeUpdate("UPDATE lastsector_schema SET version=1 WHERE id=1 AND version=0");
            }
            if(version < 2) migratePermanent(s, mysql);
            c.commit();return VERSION;
        }catch(SQLException failure){c.rollback();throw failure;}finally{c.setAutoCommit(true);}
    }
    private static void index(Statement statement,String table,String name,String columns)throws SQLException {
        var connection=statement.getConnection();
        try(var indexes=connection.getMetaData().getIndexInfo(connection.getCatalog(),null,table,false,false)) {
            while(indexes.next())if(name.equalsIgnoreCase(indexes.getString("INDEX_NAME")))return;
        }
        statement.executeUpdate("CREATE INDEX "+name+" ON "+table+" ("+columns+")");
    }
    private static void migratePermanent(Statement s, boolean mysql) throws SQLException {
        String text=mysql?"LONGTEXT":"TEXT";
        s.executeUpdate("CREATE TABLE IF NOT EXISTS player_profiles (player_uuid VARCHAR(36) PRIMARY KEY, last_known_name VARCHAR(64) NOT NULL, first_seen BIGINT NOT NULL, last_seen BIGINT NOT NULL, rating INTEGER NOT NULL, highest_rating INTEGER NOT NULL, kill_score BIGINT NOT NULL DEFAULT 0, matches BIGINT NOT NULL DEFAULT 0, wins BIGINT NOT NULL DEFAULT 0, kills BIGINT NOT NULL DEFAULT 0, deaths BIGINT NOT NULL DEFAULT 0, assists BIGINT NOT NULL DEFAULT 0, damage DOUBLE NOT NULL DEFAULT 0, play_seconds BIGINT NOT NULL DEFAULT 0, top3 BIGINT NOT NULL DEFAULT 0, best_placement INTEGER NOT NULL DEFAULT 0)");
        s.executeUpdate("CREATE TABLE IF NOT EXISTS match_results (session_id VARCHAR(36) PRIMARY KEY, completed_at BIGINT NOT NULL, completion_reason VARCHAR(32) NOT NULL, payload "+text+" NOT NULL)");
        s.executeUpdate("CREATE TABLE IF NOT EXISTS player_match_results (session_id VARCHAR(36) NOT NULL, player_uuid VARCHAR(36) NOT NULL, payload "+text+" NOT NULL, PRIMARY KEY(session_id,player_uuid))");
        s.executeUpdate("CREATE TABLE IF NOT EXISTS player_period_stats (period_type VARCHAR(8) NOT NULL, period_key VARCHAR(16) NOT NULL, player_uuid VARCHAR(36) NOT NULL, matches BIGINT NOT NULL DEFAULT 0, wins BIGINT NOT NULL DEFAULT 0, kills BIGINT NOT NULL DEFAULT 0, deaths BIGINT NOT NULL DEFAULT 0, assists BIGINT NOT NULL DEFAULT 0, damage DOUBLE NOT NULL DEFAULT 0, rating_delta BIGINT NOT NULL DEFAULT 0, kill_score_delta BIGINT NOT NULL DEFAULT 0, PRIMARY KEY(period_type,period_key,player_uuid))");
        s.executeUpdate("CREATE TABLE IF NOT EXISTS cosmetic_unlocks (player_uuid VARCHAR(36) NOT NULL, cosmetic_id VARCHAR(128) NOT NULL, unlocked_at BIGINT NOT NULL, PRIMARY KEY(player_uuid,cosmetic_id))");
        s.executeUpdate("CREATE TABLE IF NOT EXISTS cosmetic_equipped (player_uuid VARCHAR(36) NOT NULL, category VARCHAR(32) NOT NULL, cosmetic_id VARCHAR(128) NOT NULL, PRIMARY KEY(player_uuid,category))");
        s.executeUpdate("CREATE TABLE IF NOT EXISTS cosmetic_purchase_ledger (purchase_id VARCHAR(36) PRIMARY KEY, player_uuid VARCHAR(36) NOT NULL, cosmetic_id VARCHAR(128) NOT NULL, provider VARCHAR(64) NOT NULL, currency VARCHAR(128) NOT NULL, amount VARCHAR(128) NOT NULL, status VARCHAR(32) NOT NULL, created_at BIGINT NOT NULL, updated_at BIGINT NOT NULL)");
        for(String metric:java.util.List.of("rating","kill_score","wins","kills","assists","damage"))
            index(s,"player_profiles","idx_profile_"+metric,metric+",last_known_name,player_uuid");
        for(String metric:java.util.List.of("rating_delta","kill_score_delta","wins","kills","assists","damage"))
            index(s,"player_period_stats","idx_period_"+metric,"period_type,period_key,"+metric+",player_uuid");
        index(s,"cosmetic_purchase_ledger","idx_purchase_review","status,created_at,purchase_id");
        index(s,"player_match_results","idx_result_player","player_uuid,session_id");
        s.executeUpdate("UPDATE lastsector_schema SET version=2 WHERE id=1 AND version<2");
    }
}
