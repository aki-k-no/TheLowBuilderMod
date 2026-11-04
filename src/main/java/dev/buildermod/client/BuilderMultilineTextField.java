package dev.buildermod.client;

import java.awt.Toolkit;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import net.minecraft.client.gui.FontRenderer;
import org.lwjgl.input.Keyboard;

/** Small multiline editor for Minecraft 1.8.9 GUI screens. */
final class BuilderMultilineTextField {
    private final FontRenderer font;
    private int x, y, width, height, cursor;
    private boolean focused;
    private String text = "";
    BuilderMultilineTextField(FontRenderer font) { this.font = font; }
    void setBounds(int x, int y, int width, int height) { this.x=x; this.y=y; this.width=width; this.height=height; }
    String getText() { return text; }
    void setText(String value) { text=value == null ? "" : value; cursor=text.length(); }
    void setFocused(boolean value) { focused=value; }
    boolean isFocused() { return focused; }
    void draw() {
        net.minecraft.client.gui.Gui.drawRect(x-1,y-1,x+width+1,y+height+1, focused ? 0xFF55FFFF : 0xFF555555);
        net.minecraft.client.gui.Gui.drawRect(x,y,x+width,y+height,0xCC101010);
        String[] lines=text.split("\n",-1); int lineHeight=font.FONT_HEIGHT+2; int max=Math.max(1,height/lineHeight); int start=Math.max(0,lines.length-max);
        int cy=y+4; int offset=0;
        for(int i=0;i<start;i++) offset+=lines[i].length()+1;
        for(int i=start;i<lines.length && cy<y+height-2;i++) { font.drawStringWithShadow(lines[i],x+4,cy,0xFFFFFF); cy+=lineHeight; }
        if (focused && (System.currentTimeMillis()/500L)%2==0) {
            int before = Math.max(0, Math.min(cursor, text.length()));
            int last = 0;
            while (last < lines.length - 1 && before > lines[last].length()) { before -= lines[last].length() + 1; last++; }
            if (last >= start && last < start + max && last < lines.length) {
                int px=x+4+font.getStringWidth(lines[last].substring(0,Math.min(before,lines[last].length())));
                net.minecraft.client.gui.Gui.drawRect(px, y+4+(last-start)*lineHeight, px+1, y+4+(last-start)*lineHeight+font.FONT_HEIGHT,0xFFFFFFFF);
            }
        }
    }
    boolean mouseClicked(int mx,int my,int button) {
        focused=mx>=x && mx<x+width && my>=y && my<y+height; if(!focused) return false;
        String[] lines=text.split("\n",-1); int lineHeight=font.FONT_HEIGHT+2; int start=Math.max(0,lines.length-Math.max(1,height/lineHeight)); int row=Math.min(lines.length-1,start+Math.max(0,(my-y-4)/lineHeight)); int pos=0; for(int i=0;i<row;i++) pos+=lines[i].length()+1;
        String line=lines[row]; int column=0; while(column<line.length() && font.getStringWidth(line.substring(0,column+1))<mx-x-4) column++; cursor=pos+column; return true;
    }
    void keyTyped(char ch,int key) {
        if(!focused) return;
        if(Keyboard.isKeyDown(Keyboard.KEY_LCONTROL)||Keyboard.isKeyDown(Keyboard.KEY_RCONTROL)) {
            if(key==Keyboard.KEY_A) { cursor=text.length(); return; }
            if(key==Keyboard.KEY_V) { paste(); return; }
            if(key==Keyboard.KEY_C) { copy(); return; }
        }
        if(key==Keyboard.KEY_BACK) { if(cursor>0) { text=text.substring(0,cursor-1)+text.substring(cursor); cursor--; } return; }
        if(key==Keyboard.KEY_DELETE) { if(cursor<text.length()) text=text.substring(0,cursor)+text.substring(cursor+1); return; }
        if(key==Keyboard.KEY_LEFT) { if(cursor>0) cursor--; return; }
        if(key==Keyboard.KEY_RIGHT) { if(cursor<text.length()) cursor++; return; }
        if(key==Keyboard.KEY_HOME) { while(cursor>0 && text.charAt(cursor-1)!='\n') cursor--; return; }
        if(key==Keyboard.KEY_END) { while(cursor<text.length() && text.charAt(cursor)!='\n') cursor++; return; }
        if(key==Keyboard.KEY_RETURN||key==Keyboard.KEY_NUMPADENTER) { insert("\n"); return; }
        if(ch>=32 && ch!=127) insert(Character.toString(ch));
    }
    private void insert(String value) { if(text.length()+value.length()>4000) return; text=text.substring(0,cursor)+value+text.substring(cursor); cursor+=value.length(); }
    private void copy() { try { Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text),null); } catch(Exception ignored){} }
    private void paste() { try { Object value=Toolkit.getDefaultToolkit().getSystemClipboard().getData(DataFlavor.stringFlavor); if(value!=null) insert(value.toString().replace("\r\n","\n").replace('\r','\n')); } catch(Exception ignored){} }
}
