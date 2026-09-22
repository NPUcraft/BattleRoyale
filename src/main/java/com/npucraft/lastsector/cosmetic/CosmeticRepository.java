package com.npucraft.lastsector.cosmetic;
import com.npucraft.lastsector.storage.StorageProvider;
import java.sql.*;
import java.util.*;
import java.math.BigDecimal;
public final class CosmeticRepository {
    private final StorageProvider provider;
    public CosmeticRepository(StorageProvider provider){this.provider=provider;}
    public boolean owns(UUID player,String id)throws SQLException {
        try(var c=provider.connect()){return owns(c,player,id);}
    }
    private boolean owns(Connection c,UUID player,String id)throws SQLException {
        try(var s=c.prepareStatement("SELECT 1 FROM cosmetic_unlocks WHERE player_uuid=? AND cosmetic_id=?")) {
            s.setString(1,player.toString());s.setString(2,id);try(var r=s.executeQuery()){return r.next();}
        }
    }
    private void unlock(Connection c,UUID player,String id,long now)throws SQLException {
        String insert=provider.type().equals("mysql")?"INSERT IGNORE":"INSERT OR IGNORE";
        try(var s=c.prepareStatement(insert+" INTO cosmetic_unlocks(player_uuid,cosmetic_id,unlocked_at) VALUES(?,?,?)")) {
            s.setString(1,player.toString());s.setString(2,id);s.setLong(3,now);s.executeUpdate();
        }
    }
    public void grant(UUID player,String id,long now)throws SQLException {try(var c=provider.connect()){unlock(c,player,id,now);}}
    public void revoke(UUID player,String id)throws SQLException {
        try(var c=provider.connect()) {
            c.setAutoCommit(false);try {
                for(String table:List.of("cosmetic_equipped","cosmetic_unlocks"))try(var s=c.prepareStatement("DELETE FROM "+table+" WHERE player_uuid=? AND cosmetic_id=?")) {s.setString(1,player.toString());s.setString(2,id);s.executeUpdate();}
                c.commit();
            }catch(SQLException e){c.rollback();throw e;}
        }
    }
    public void equip(UUID player,CosmeticCategory category,String id)throws SQLException {
        try(var c=provider.connect()) {
            c.setAutoCommit(false);try {
                if(id!=null && !owns(c,player,id))throw new SQLException("Cosmetic not owned");
                try(var s=c.prepareStatement("DELETE FROM cosmetic_equipped WHERE player_uuid=? AND category=?")) {s.setString(1,player.toString());s.setString(2,category.name());s.executeUpdate();}
                if(id!=null)try(var s=c.prepareStatement("INSERT INTO cosmetic_equipped(player_uuid,category,cosmetic_id) VALUES(?,?,?)")) {s.setString(1,player.toString());s.setString(2,category.name());s.setString(3,id);s.executeUpdate();}
                c.commit();
            }catch(SQLException e){c.rollback();throw e;}
        }
    }
    public void intent(Purchase p)throws SQLException {
        try(var c=provider.connect();var s=c.prepareStatement("INSERT INTO cosmetic_purchase_ledger(purchase_id,player_uuid,cosmetic_id,provider,currency,amount,status,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?)")) {
            s.setString(1,p.id().toString());s.setString(2,p.player().toString());s.setString(3,p.cosmetic());s.setString(4,p.provider());s.setString(5,p.currency());s.setString(6,p.amount().toPlainString());s.setString(7,p.status().name());s.setLong(8,p.createdAt());s.setLong(9,p.updatedAt());s.executeUpdate();
        }
    }
    public void transition(UUID id,Purchase.Status expected,Purchase.Status next,long now)throws SQLException {
        try(var c=provider.connect()){transition(c,id,expected,next,now);}
    }
    private void transition(Connection c,UUID id,Purchase.Status expected,Purchase.Status next,long now)throws SQLException {
        try(var s=c.prepareStatement("UPDATE cosmetic_purchase_ledger SET status=?,updated_at=? WHERE purchase_id=? AND status=?")) {
            s.setString(1,next.name());s.setLong(2,now);s.setString(3,id.toString());s.setString(4,expected.name());if(s.executeUpdate()!=1)throw new SQLException("Purchase status conflict");
        }
    }
    public void complete(Purchase p)throws SQLException {
        try(var c=provider.connect()) {
            c.setAutoCommit(false);try {
                transition(c,p.id(),Purchase.Status.WITHDRAWING,Purchase.Status.COMPLETED,System.currentTimeMillis());
                unlock(c,p.player(),p.cosmetic(),System.currentTimeMillis());c.commit();
            }catch(SQLException e){c.rollback();throw e;}
        }
    }
    public Purchase.Status status(UUID id)throws SQLException {
        try(var c=provider.connect();var s=c.prepareStatement("SELECT status FROM cosmetic_purchase_ledger WHERE purchase_id=?")) {
            s.setString(1,id.toString());try(var rows=s.executeQuery()){if(!rows.next())throw new SQLException("Missing purchase");return Purchase.Status.valueOf(rows.getString(1));}
        }
    }
    public void quarantineAmbiguous()throws SQLException {
        try(var c=provider.connect();var s=c.prepareStatement("UPDATE cosmetic_purchase_ledger SET status='MANUAL_REVIEW',updated_at=? WHERE status IN ('PENDING','WITHDRAWING')")) {s.setLong(1,System.currentTimeMillis());s.executeUpdate();}
    }
    public long manualReviewCount()throws SQLException{try(var c=provider.connect();var s=c.prepareStatement("SELECT COUNT(*) FROM cosmetic_purchase_ledger WHERE status='MANUAL_REVIEW'");var rows=s.executeQuery()){rows.next();return rows.getLong(1);}}
    public List<Purchase> manualReview(int page)throws SQLException {
        if(page<0 || page>Integer.MAX_VALUE/40)throw new IllegalArgumentException("Invalid page");
        try(var c=provider.connect();var s=c.prepareStatement("SELECT * FROM cosmetic_purchase_ledger WHERE status='MANUAL_REVIEW' ORDER BY created_at,purchase_id LIMIT 40 OFFSET ?")) {
            s.setInt(1,page*40);var rows=new ArrayList<Purchase>();try(var r=s.executeQuery()) {while(r.next()) rows.add(new Purchase(UUID.fromString(r.getString("purchase_id")),UUID.fromString(r.getString("player_uuid")),r.getString("cosmetic_id"),r.getString("provider"),r.getString("currency"),new BigDecimal(r.getString("amount")),Purchase.Status.valueOf(r.getString("status")),r.getLong("created_at"),r.getLong("updated_at")));}return List.copyOf(rows);
        }
    }
}
