package com.npucraft.lastsector.combat;
/** Total spendable XP represented by current level + progress, not lifetime getTotalExperience(). */
public final class ExperienceMath {
    private ExperienceMath() {}
    public static int total(int level,float progress) {
        if(level<0 || !Float.isFinite(progress) || progress<0 || progress>=1) throw new IllegalArgumentException("Invalid XP state");
        double base=level<=16?(double)level*level+6d*level:level<=31?2.5d*level*level-40.5d*level+360:4.5d*level*level-162.5d*level+2220;
        double next=level<=15?2d*level+7:level<=30?5d*level-38:9d*level-158;
        // Paper's float progress representation needs nearest-integer recovery of whole earned points.
        return (int)Math.min(Integer.MAX_VALUE,base+Math.round(progress*next));
    }
    public static int stored(int total) { if(total<0) throw new IllegalArgumentException("Negative XP"); return total/2; }
    public static boolean validBottle(Integer amount) { return amount!=null && amount>0 && amount<=Integer.MAX_VALUE/2; }
}
