import io.github.junara.avpreview.NativePlayer;
import javax.swing.SwingUtilities;

public class NativeSmoke {
    static void require(boolean ok,String message) { if(!ok) throw new AssertionError(message); }
    public static void main(String[] args) {
        try {
            if (!Boolean.getBoolean("avpreview.headlessSmoke")) SwingUtilities.invokeAndWait(() -> new javax.swing.JPanel());
            NativePlayer.load();
            require(NativePlayer.open("/does-not-exist/avpreview.mp4")==0,"missing path");
            require(NativePlayer.info(0,0)==null,"info for unknown handle");
            for(int round=0; round<3; round++) {
                long id=NativePlayer.open(args[0]);
                require(id!=0,"open");
                try {
                    long deadline=System.nanoTime()+15_000_000_000L;
                    double[] state;
                    do {
                        state=NativePlayer.state(id);
                        if(state[0]==2) throw new AssertionError(NativePlayer.error(id));
                        Thread.sleep(30);
                    } while(state[0]!=1 && System.nanoTime()<deadline);
                    require(state[0]==1,"ready timeout");
                    require(state[2]>3,"duration");
                    String info=NativePlayer.info(id,new java.io.File(args[0]).length());
                    require(info!=null,"info when ready");
                    for(String expected: new String[]{"Video\n","Duration\t00:00:05","Overall bitrate\t","Codec\tH.264 (avc1)","Resolution\t1920 × 1080","Frame rate\t30 fps",
                            "Audio\n","Codec\tAAC (aac)","Sample rate\t48000 Hz","Channels\t1 (mono)"})
                        require(info.contains(expected),"info should contain "+expected.strip()+" in:\n"+info);
                    if(round==0) System.out.print(info);
                    NativePlayer.volume(id,0); // Test must not play sound in the user's room.
                    NativePlayer.play(id,1);
                    int count=0, allocations=0; int w=0,h=0; int[] buffer=null;
                    for(int i=0;i<40;i++) {
                        int r=NativePlayer.frame(id,buffer);
                        if(r<0) {
                            buffer=new int[(-r>>>16)*(-r&0xFFFF)]; allocations++;
                            r=NativePlayer.frame(id,buffer);
                            require(r>0,"frame kept for retry");
                        }
                        if(r>0) {
                            w=r>>>16;h=r&0xFFFF;
                            require(buffer.length==w*h,"pixel size");
                            require(Math.max(w,h)<=1280,"preview bounds");
                            for(int p=0;p<buffer.length;p++)
                                if((buffer[p]>>>24)!=0xFF) throw new AssertionError("opaque xRGB pixel at "+p);
                            count++;
                        }
                        Thread.sleep(33);
                    }
                    require(allocations==1,"buffer reused: allocations="+allocations);
                    require(count>5,"decoded frames: "+count);
                    require(NativePlayer.state(id)[1]>0.5,"time advances");
                    NativePlayer.pause(id);
                    double paused=NativePlayer.state(id)[1];Thread.sleep(200);
                    require(Math.abs(NativePlayer.state(id)[1]-paused)<0.1,"pause");
                    NativePlayer.seek(id,2.5);Thread.sleep(300);
                    require(Math.abs(NativePlayer.state(id)[1]-2.5)<0.15,"seek");
                    NativePlayer.play(id,1.5);Thread.sleep(200);
                    require(NativePlayer.state(id)[3]>1,"rate");
                    System.out.println("PASS round="+round+" frames="+count+" size="+w+"x"+h+" duration="+state[2]);
                } finally { NativePlayer.close(id); NativePlayer.close(id); }
            }
            if(args.length>1) checkColorOrder(args[1]);
            if(args.length>2) checkInfo(args[2],new String[]{"Frame rate\t29.97 fps","Duration\t00:00:01.001"},new String[0]);
            if(args.length>4) {
                checkInfo(args[3],new String[]{"Rotation\t270° clockwise","Displayed size\t360 × 640"},new String[]{"Mirrored"});
                checkInfo(args[4],new String[]{"Mirrored\tHorizontally\n"},new String[]{"Rotation"});
            }
            String hdr=System.getProperty("avpreview.hdrPair"); // "<sdr>|<pq>"
            if(hdr!=null) { String[] pair=hdr.split("\\|"); checkHdrToSdr(pair[0],pair[1]); }
            System.out.println("PASS native open/decode/play/pause/seek/rate/close");
            System.exit(0);
        } catch(Throwable t) { t.printStackTrace(); System.exit(1); }
    }

    /** Fixture: left half red, right half blue. Verifies BGRA bytes arrive as xRGB ints. */
    static void checkColorOrder(String path) throws Exception {
        Frame f=firstFrame(path); int w=f.width(),h=f.height();
        int left=f.pixels()[(h/2)*w+w/4], right=f.pixels()[(h/2)*w+3*w/4];
        String detail=String.format(" left=%06X right=%06X",left&0xFFFFFF,right&0xFFFFFF);
        require(red(left)>180 && blue(left)<80 && green(left)<80,"left half should be red"+detail);
        require(blue(right)>180 && red(right)<80 && green(right)<80,"right half should be blue"+detail);
        System.out.println("PASS color order"+detail);
    }
    /**
     * Fixtures: the same mid-gray code value tagged BT.709 and PQ. PQ ~51% is ~100 nits, near SDR white,
     * so a real HDR→SDR conversion must brighten it; identical output means the PQ values passed through.
     */
    static void checkHdrToSdr(String sdr,String pq) throws Exception {
        double a=meanLuma(firstFrame(sdr)), b=meanLuma(firstFrame(pq));
        String detail=String.format(" sdr=%.1f pq=%.1f",a,b);
        require(b>a+30,"PQ should be tone-mapped brighter than the same SDR code value"+detail);
        System.out.println("PASS HDR to SDR"+detail);
    }
    record Frame(int width,int height,int[] pixels) {}
    /** Returns the first decoded frame. */
    static Frame firstFrame(String path) throws Exception {
        long id=NativePlayer.open(path);
        require(id!=0,"open "+path);
        try {
            long deadline=System.nanoTime()+10_000_000_000L;
            while(NativePlayer.state(id)[0]==0 && System.nanoTime()<deadline) Thread.sleep(30);
            double status=NativePlayer.state(id)[0];
            if(status==2) throw new AssertionError(path+" failed: "+NativePlayer.error(id));
            require(status==1,path+" ready timeout");
            NativePlayer.volume(id,0);
            NativePlayer.play(id,1);
            int[] buffer=null; int r=0;
            while(r<=0 && System.nanoTime()<deadline) {
                r=NativePlayer.frame(id,buffer);
                if(r<0) { buffer=new int[(-r>>>16)*(-r&0xFFFF)]; r=NativePlayer.frame(id,buffer); }
                if(r<=0) Thread.sleep(33);
            }
            require(r>0,path+" frame");
            return new Frame(r>>>16,r&0xFFFF,buffer);
        } finally { NativePlayer.close(id); }
    }
    static double meanLuma(Frame frame) {
        double sum=0;
        for(int p:frame.pixels()) sum+=0.2126*red(p)+0.7152*green(p)+0.0722*blue(p);
        return sum/frame.pixels().length;
    }
    /** Every info row must contain all of expected, none of unexpected, and report no load problems. */
    static void checkInfo(String path,String[] expected,String[] unexpected) {
        long id=NativePlayer.open(path);
        require(id!=0,"open "+path);
        try {
            String info=NativePlayer.info(id,new java.io.File(path).length());
            require(info!=null,"info "+path);
            for(String e: expected) require(info.contains(e),path+" info should contain "+e.strip()+" in:\n"+info);
            for(String u: unexpected) require(!info.contains(u),path+" info should not contain "+u+" in:\n"+info);
            require(!info.contains("Metadata\t"),path+" should load all metadata:\n"+info);
            System.out.println("PASS info "+path.substring(path.lastIndexOf('/')+1));
        } finally { NativePlayer.close(id); }
    }
    static int red(int p) { return (p>>16)&0xFF; }
    static int green(int p) { return (p>>8)&0xFF; }
    static int blue(int p) { return p&0xFF; }
}
