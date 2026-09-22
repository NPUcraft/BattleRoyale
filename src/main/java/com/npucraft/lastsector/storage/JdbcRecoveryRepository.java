package com.npucraft.lastsector.storage;
import java.sql.*;
import java.util.*;
/** JDBC-only repository, invoked exclusively by DatabaseExecutor. */
public final class JdbcRecoveryRepository implements RecoveryRepository {
    private final StorageProvider provider;
    public JdbcRecoveryRepository(StorageProvider provider){this.provider=provider;}
    public int migrate()throws Exception{try(var c=provider.connect()){return SchemaMigrations.migrate(c,provider.type());}}
    private static void bind(PreparedStatement s,Object... args)throws SQLException{for(int i=0;i<args.length;i++)s.setObject(i+1,args[i] instanceof UUID id?id.toString():args[i]);}
    public List<Row> sessions()throws Exception {
        try(var c=provider.connect();var s=c.prepareStatement("SELECT * FROM recovery_sessions WHERE status IN ('ACTIVE','RECOVERING')");var r=s.executeQuery()) {
            var rows=new ArrayList<Row>();while(r.next())rows.add(new Row(UUID.fromString(r.getString("session_id")),r.getString("room_id"),r.getString("map_id"),r.getString("world_name"),r.getString("game_state"),r.getLong("revision"),r.getInt("format_version"),r.getString("payload"),r.getString("checksum"),r.getLong("updated_at"),r.getString("status")));return List.copyOf(rows);
        }
    }
    public boolean save(Row row,UUID owner,long leaseUntil)throws Exception {
        try(var c=provider.connect()) {c.setAutoCommit(false);try {
            int changed;
            try(var s=c.prepareStatement("INSERT INTO recovery_sessions(session_id,room_id,map_id,world_name,game_state,revision,format_version,payload,checksum,updated_at,status,owner_token,lease_until) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)"+(provider.type().equals("sqlite")?" ON CONFLICT(session_id) DO NOTHING":""))) {
                bind(s,row.session(),row.room(),row.map(),row.world(),row.state(),row.revision(),row.version(),row.payload(),row.checksum(),row.updatedAt(),"ACTIVE",owner,leaseUntil);changed=s.executeUpdate();
            }catch(SQLException duplicate){if(provider.type().equals("mysql") && duplicate.getErrorCode()==1062)changed=0;else throw duplicate;}
            if(changed==0)try(var s=c.prepareStatement("UPDATE recovery_sessions SET room_id=?,map_id=?,world_name=?,game_state=?,revision=?,format_version=?,payload=?,checksum=?,updated_at=?,status='ACTIVE',owner_token=?,lease_until=? WHERE session_id=? AND revision<? AND owner_token=? AND status IN ('ACTIVE','RECOVERING')")) {
                bind(s,row.room(),row.map(),row.world(),row.state(),row.revision(),row.version(),row.payload(),row.checksum(),row.updatedAt(),owner,leaseUntil,row.session(),row.revision(),owner);changed=s.executeUpdate();
            }catch(SQLException duplicate){if(provider.type().equals("mysql") && duplicate.getErrorCode()==1062)changed=0;else throw duplicate;}
            c.commit();return changed>0;
        }catch(Exception failure){c.rollback();throw failure;}}
    }
    public boolean claim(UUID id,UUID owner,long now,long until)throws Exception {
        try(var c=provider.connect();var s=c.prepareStatement("UPDATE recovery_sessions SET owner_token=?,lease_until=? WHERE session_id=? AND status IN ('ACTIVE','RECOVERING') AND (owner_token=? OR lease_until<=?)")){bind(s,owner,until,id,owner,now);return s.executeUpdate()==1;}
    }
    public void retire(UUID id,UUID owner,String status)throws Exception {
        if(!Set.of("ABANDONED","COMPLETED").contains(status))throw new IllegalArgumentException("Invalid retirement");
        try(var c=provider.connect()){c.setAutoCommit(false);try {
            try(var s=c.prepareStatement("UPDATE recovery_sessions SET status=?,lease_until=0 WHERE session_id=? AND owner_token=?")){bind(s,status,id,owner);if(s.executeUpdate()!=1)throw new SQLException("Recovery retirement ownership lost");}
            try(var s=c.prepareStatement("UPDATE pending_player_restores SET status='PENDING' WHERE session_id=? AND status='ORIGINAL'")){bind(s,id);s.executeUpdate();}
            c.commit();
        }catch(Exception error){c.rollback();throw error;}}
    }
    public void originals(List<Restore> rows)throws Exception {
        try(var c=provider.connect()){c.setAutoCommit(false);try {
            for(var row:rows)try(var s=c.prepareStatement("INSERT INTO pending_player_restores(player_uuid,session_id,generation,format_version,payload,checksum,status,created_at) VALUES(?,?,?,?,?,?,?,?)")){bind(s,row.player(),row.session(),row.generation(),row.version(),row.payload(),row.checksum(),row.status(),row.createdAt());s.executeUpdate();}
            c.commit();
        }catch(Exception error){c.rollback();throw error;}}
    }
    public List<Restore> restores()throws Exception {
        try(var c=provider.connect();var s=c.prepareStatement("SELECT * FROM pending_player_restores");var r=s.executeQuery()) {
            var rows=new ArrayList<Restore>();while(r.next())rows.add(new Restore(UUID.fromString(r.getString("player_uuid")),UUID.fromString(r.getString("session_id")),UUID.fromString(r.getString("generation")),r.getInt("format_version"),r.getString("payload"),r.getString("checksum"),r.getString("status"),r.getLong("created_at")));return List.copyOf(rows);
        }
    }
    public void pending(UUID session)throws Exception {try(var c=provider.connect();var s=c.prepareStatement("UPDATE pending_player_restores SET status='PENDING' WHERE session_id=? AND status='ORIGINAL'")){bind(s,session);s.executeUpdate();}}
    public void pendingPlayer(UUID player,UUID generation)throws Exception {try(var c=provider.connect();var s=c.prepareStatement("UPDATE pending_player_restores SET status='PENDING' WHERE player_uuid=? AND generation=? AND status='ORIGINAL'")){bind(s,player,generation);s.executeUpdate();}}
    public boolean applied(UUID player,UUID generation)throws Exception {try(var c=provider.connect();var s=c.prepareStatement("UPDATE pending_player_restores SET status='APPLIED' WHERE player_uuid=? AND generation=?")){bind(s,player,generation);return s.executeUpdate()==1;}}
    public void deleteRestore(UUID player,UUID generation)throws Exception {try(var c=provider.connect();var s=c.prepareStatement("DELETE FROM pending_player_restores WHERE player_uuid=? AND generation=? AND status='APPLIED'")){bind(s,player,generation);s.executeUpdate();}}
}
