import dev.nonamecrackers2.simpleclouds.client.renderer.v2.ChunkBufferPool;
public class ChunkBufferPoolTest {
    public static void main(String[] args) {
        var pool = new ChunkBufferPool();
        for (int i=0;i<1000;i++) {
            var b=pool.borrow(1024);
            if (pool.retainedBytes()!=0) throw new AssertionError("borrow did not release retained-byte count");
            pool.release(b);
            if (pool.retainedBytes()!=1024 || pool.freeCount()!=1) throw new AssertionError("incorrect pool ownership accounting");
        }
        if (pool.totalAllocations()!=1) throw new AssertionError("reuse regression");
        System.out.println("PASS: 1000 pool borrow/release cycles reuse one allocation with exact byte accounting");
    }
}
