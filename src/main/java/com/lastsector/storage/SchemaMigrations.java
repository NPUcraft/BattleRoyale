package com.lastsector.storage;
import java.sql.*;
/** V1 DDL is restartable on MySQL (DDL auto-commits); SQLite rolls DDL back transactionally. */
public final class SchemaMigrations {
    public static final int VERSION=1;
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
            c.commit();return VERSION;
        }catch(SQLException failure){c.rollback();throw failure;}finally{c.setAutoCommit(true);}
    }
}
