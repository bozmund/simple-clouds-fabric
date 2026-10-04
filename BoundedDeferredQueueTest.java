import dev.nonamecrackers2.simpleclouds.client.dh.BoundedDeferredQueue;

public class BoundedDeferredQueueTest {
    static void require(boolean condition,String message) {
        if(!condition) throw new AssertionError(message);
    }
    public static void main(String[] args) throws Exception {
        try {new BoundedDeferredQueue<>(0);throw new AssertionError("zero capacity accepted");}
        catch(IllegalArgumentException expected) {}
        var q=new BoundedDeferredQueue<Integer>(3);
        require(q.poll()==null,"empty result");
        require(!q.offer(1)&&!q.offer(2)&&!q.offer(3),"premature discard");
        try {q.offer(null);throw new AssertionError("null accepted");}
        catch(NullPointerException expected) {}
        require(q.size()==3,"null offer changed queue");
        require(q.offer(4),"overflow not reported");
        require(q.poll()==2&&q.poll()==3&&q.poll()==4&&q.poll()==null,"FIFO/oldest discard");
        var concurrent=new BoundedDeferredQueue<Integer>(32);
        Thread[] producers=new Thread[8];
        for(int n=0;n<producers.length;n++) {
            int producer=n;
            producers[n]=new Thread(()->{for(int i=0;i<1000;i++) concurrent.offer(producer*1000+i);});
            producers[n].start();
        }
        for(Thread t:producers) t.join();
        require(concurrent.size()==32,"cross-thread capacity violated");
        var retained=new java.util.HashSet<Integer>();
        Integer value;
        while((value=concurrent.poll())!=null) require(retained.add(value),"duplicate entry");
        require(retained.size()==32&&q.size()==0,"queue isolation/drain");
        System.out.println("PASS deferred queue FIFO, explicit overflow, null rejection, 8000 concurrent offers and isolation");
    }
}
