import java.util.zip.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ConcurrentHashMap;

////////////////////////////////////////////////////////////////////////////////////
// Blocking Map
////////////////////////////////////////////////////////////////////////////////////
/*
    - ConcurrentHashMap with block index as queue and BlockingQueues as value
    - Maintains queues of only size 1 for output buffers
    - When output buffer requested with a given index, queue[index].take() is called
        This blocks the method till output buffer of index is put into the BlockingMap
        Helps maintain order while writing output
    
    A similar strucutre was suggested on piazza: 
    https://stackoverflow.com/questions/23917818/concurrenthashmap-wait-for-key-possible
    Changes were made to make it closer to MessAdmin does with their BlockingQueue
*/

final class BlockingMap<K, V> 
// Implementation inspired from 
// https://stackoverflow.com/questions/23917818/concurrenthashmap-wait-for-key-possible
// Queue functioncs changed to suit our needs
{
    private ConcurrentHashMap<K, BlockingQueue<V>> map;
    
    public BlockingMap(int blocks_to_handle)
    {
        this.map = new ConcurrentHashMap<K, BlockingQueue<V>>(blocks_to_handle + 2, (float)1.0, blocks_to_handle);
    }

    private synchronized BlockingQueue<V> ensureQueueExists(K key) 
    {
        return map.computeIfAbsent(key, k -> new ArrayBlockingQueue<>(1));
    }

    public void put(K key, V value)
    {
        BlockingQueue<V> queue = ensureQueueExists(key);
        try
        {
            queue.put(value);
        }
        catch (InterruptedException e) 
        {
            throw new IllegalStateException(e);
        }
    }

    public V get(K key)
    {
        BlockingQueue<V> queue = ensureQueueExists(key);
        try 
        {
            return queue.take();
        } 
        catch (InterruptedException e) 
        {
            throw new IllegalStateException(e);
        }
    }
}

////////////////////////////////////////////////////////////////////////////////////
// Writer
////////////////////////////////////////////////////////////////////////////////////
/*
    - Handles the only shared variables between blocks 
    - Maintains BlockingMap between block index and output buffer
    - Maintains AtomicIntegerArray to store the amount of relevants 
        bytes in each output buffer (will beupdated in parallel) 
    - BlockCompressor can ask writer to note completed output buffer
        Adds them to BlockingMap with corresponding index
        (called in parallel)
    - Can write out all the blocks stored in BlockingMap and checks for errors 
*/

final class Writer
{
    public final static int BLOCK_SIZE = 131072;
    
    public int prepare_for;
    public int start_from;

    public BlockingMap<Integer, byte[]> write_out;
    public AtomicIntegerArray deflatedBytes_arr;
    
    public CRC32 crc;
    
    public Writer(int prepare_for, int start_from) throws FileNotFoundException, IOException
    {
        this.prepare_for = prepare_for;
        this.start_from  = start_from;

        this.deflatedBytes_arr = new AtomicIntegerArray(prepare_for);

        this.write_out = new BlockingMap<>(prepare_for);
    }

    public void noteBlock(AtomicInteger n_block, byte[] cmpBlockBuf, AtomicInteger deflatedBytes)
    {
        // Account for start of batch to process block index
        AtomicInteger idx = new AtomicInteger(-this.start_from);
        idx.addAndGet(n_block.get());
        // Note the number of relevant bytes
        this.deflatedBytes_arr.set(idx.get(), deflatedBytes.get());
        // Add given output buffer to blocking map 
        this.write_out.put(idx.get(), cmpBlockBuf);
    }

    public void writeAllBlocks()
    {
        for(int i = 0; i < this.prepare_for; i++)
        {
            if(this.deflatedBytes_arr.get(i) > 0)
            {
                System.out.write(write_out.get(i), 0, this.deflatedBytes_arr.get(i));
            }
        }
        // Check for errors
        if (System.out.checkError()) 
        {
            System.err.println("Error: Something went wrong with stdout!");
            System.exit(1);
        }
    }
}

////////////////////////////////////////////////////////////////////////////////////
// Block Compressor
////////////////////////////////////////////////////////////////////////////////////
/*
    - Runnable, run() is executed in parallel
    - Reads in 1 block from stdin
    - Updates crc while reading (so it is done in order)
    - Gets dictionary from previous block if exists
    - Has its own Deflater object
    - Compresses input in run() and sends output to be stored by writer
*/

final class BlockCompressor implements Runnable
{
    public final static int BLOCK_SIZE = 131072;
    public final static int DICT_SIZE = 32768;
    
    public Writer writer;

    public CRC32 crc;
    public Deflater compressor;

    public AtomicInteger n_block;

    public int nBytes = 0;
    public byte[] blockBuf = new byte[BLOCK_SIZE];
    public byte[] dictBuf  = new byte[DICT_SIZE];
    public byte[] cmpBlockBuf = new byte[BLOCK_SIZE];

    public BlockCompressor prevBlock;
    public boolean isLast;
    
    public BlockCompressor(int n_block, CRC32 crc, Writer writer, BlockCompressor prevBlock) 
                            throws FileNotFoundException, IOException 
    {
        this.n_block = new AtomicInteger(n_block);

        // Writer and crc are shared
        this.writer = writer;
        this.crc = crc;

        // Each block has its own compressor
        this.compressor = new Deflater(Deflater.DEFAULT_COMPRESSION, true);

        this.prevBlock = prevBlock;
        this.isLast = false;
    }

    public void setLast()
    {
        this.isLast = true;
    }

    public void setWriter(Writer writer)
    {
        this.writer = writer;
    }

    // Read input must be run in the order that the blocks are to be placed
    public final int readInput() throws FileNotFoundException, IOException 
    {
        // Read in
        this.nBytes = System.in.read(this.blockBuf);
        
        // If input is over with
        if(this.nBytes < 0)
        {
            return this.nBytes;
        }

        this.crc.update(this.blockBuf, 0, this.nBytes);

        // Copy dict from prev block
        if(this.prevBlock != null && this.prevBlock.nBytes >= DICT_SIZE)
        {
            System.arraycopy(this.prevBlock.blockBuf, 
                            this.prevBlock.nBytes - DICT_SIZE, 
                            dictBuf, 0, DICT_SIZE);
        }
        
        return this.nBytes;
    }

    public final void run()
    {
        // If not first block
        if(this.prevBlock != null)
        {
            // Set dictionary 
            this.compressor.setDictionary(this.dictBuf);
        }

        // Set input for compressor
        this.compressor.setInput(this.blockBuf, 0, this.nBytes);

        //Check if last block 
        if(this.isLast)
        {   
            /* If we've read all the bytes in the file, this is the last block.
                We have to clean out the deflater properly */
            if (!this.compressor.finished()) 
            {   
                this.compressor.finish(); 
                while (!this.compressor.finished()) 
                { 
                    AtomicInteger deflatedBytes = new AtomicInteger(this.compressor.deflate(this.cmpBlockBuf, 0, this.cmpBlockBuf.length, Deflater.NO_FLUSH));
                    this.writer.noteBlock(this.n_block, this.cmpBlockBuf, deflatedBytes);
                }
            }            
        }
        // If not, compress regularly
        else 
        {
            /* Otherwise, just deflate and then write the compressed block out. Not using SYNC_FLUSH here leads to
                some issues, but using it probably results in less efficient compression. There's probably a better
                way to deal with this. */
            AtomicInteger deflatedBytes = new AtomicInteger(this.compressor.deflate(this.cmpBlockBuf, 0, this.cmpBlockBuf.length, Deflater.SYNC_FLUSH));
            this.writer.noteBlock(this.n_block, this.cmpBlockBuf, deflatedBytes);
        }  
    }
}
////////////////////////////////////////////////////////////////////////////////////
// MultiThreaded Compressor 
////////////////////////////////////////////////////////////////////////////////////
/*
    - Handles all overhead such as writing headers and trailers 
    - Makes the thread pool
    - Makes batches of num_threads blocks and compresses them in parallel
        * Each batch gets its own Writer
        * Each batch creates num_threads blocks and gets one block from the prev batch (for dict)
        * Each batch compresses num_threads blocks and passes its last block to the next batch
        * Compression happens by usung the threadpool 
        * Once compression is complete, output for the batch is written and we move to the next 

    The idea of reading in a batch at a time is inspired from pigz
*/

final class MultiThreadedCompressor 
{
    private final static int GZIP_MAGIC = 0x8b1f;
    private final static int TRAILER_SIZE = 8;
    
    public int num_threads;
    public long readBytes;
    public CRC32 crc = new CRC32();

    public MultiThreadedCompressor(int num_threads) throws FileNotFoundException, IOException
    {
        this.num_threads = num_threads;
        this.readBytes = 0;
    }

    /////////////////////////////////////////////////////////////////////////////////////////
    // Writers

    public void writeHeader() throws IOException 
    {
        System.out.write(new byte[] 
                    {
                      (byte) GZIP_MAGIC,        // Magic number (short)
                      (byte)(GZIP_MAGIC >> 8),  // Magic number (short)
                      Deflater.DEFLATED,        // Compression method (CM)
                      0,                        // Flags (FLG)
                      0,                        // Modification time MTIME (int)
                      0,                        // Modification time MTIME (int)
                      0,                        // Modification time MTIME (int)
                      0,                        // Modification time MTIME (int)Sfil
                      0,                        // Extra flags (XFLG)
                      0                         // Operating system (OS)
                    });
    }


    /*
     * Writes GZIP member trailer to a byte array, starting at a given
     * offset.
     */
    public void writeTrailer(int crc_val, long totalBytes, int offset) throws IOException 
    {
        byte[] buf = new byte[TRAILER_SIZE];
        
        writeInt(crc_val, buf, offset); // CRC-32 of uncompr. data
        writeInt((int)totalBytes, buf, offset + 4); // Number of uncompr. bytes
        
        System.out.write(buf); 
    }

    /*
     * Writes integer in Intel byte order to a byte array, starting at a
     * given offset.
     */
    public void writeInt(int i, byte[] buf, int offset) throws IOException 
    {
        writeShort(i & 0xffff, buf, offset);
        writeShort((i >> 16) & 0xffff, buf, offset + 2);
    }

    /*
     * Writes short integer in Intel byte order to a byte array, starting
     * at a given offset
     */
    public void writeShort(int s, byte[] buf, int offset) throws IOException 
    {
        buf[offset] = (byte)(s & 0xff);
        buf[offset + 1] = (byte)((s >> 8) & 0xff);
    }

    public void doEmptyFile() throws IOException
    {
        
        byte[] blockBuf = new byte[10];
        byte[] cmpBlockBuf = new byte[10];
        
        Deflater compressor = new Deflater(Deflater.DEFAULT_COMPRESSION, true);
        
        // Set empty byte array as input
        compressor.setInput(blockBuf, 0, 0);

        // Do finishing procedure and write out 
        compressor.finish(); 
        while (!compressor.finished()) 
        { 
            int deflatedBytes = compressor.deflate(cmpBlockBuf, 0, cmpBlockBuf.length, Deflater.NO_FLUSH);
            System.out.write(cmpBlockBuf, 0, deflatedBytes);
        }
        if (System.out.checkError()) 
        {
            System.err.println("Error: Something went wrong with stdout!");
            System.exit(1);
        }
    }

    ////////////////////////////////////////////////////////////////////////////
    // Compressor
    public BlockCompressor compressBatch(ThreadPoolExecutor executor, BlockCompressor prevBlock) 
                                         throws IOException, FileNotFoundException, InterruptedException, ExecutionException
    {   
        //Notes:
        //  The function makes an array of num_threads + 1 BlockCompressors
        //  The first of these is passed to the function. It has been read but not run
        //  The last of these is not run, but is read
        
        // Make writer 
        int start_from = prevBlock.n_block.get();
        Writer writer = new Writer(this.num_threads, start_from);      

        // Construct block compressors for batch
        BlockCompressor[] blocks = new BlockCompressor[this.num_threads + 1];
         
        // Note previous block and set current writer
        blocks[0] = prevBlock;
        blocks[0].setWriter(writer);

        // Construct and read blocks
        int go_upto = this.num_threads;
        for(int i = 1; i < this.num_threads + 1; i++)
        {
            // Make block
            blocks[i] = new BlockCompressor(start_from + i, this.crc, writer, blocks[i - 1]);

            // Read the block
            int nBytes = blocks[i].readInput();

            // End of input reached
            if(nBytes < 0)
            {
                blocks[i-1].setLast();
                go_upto = i;
                break;
            }

            // Note the bytes read
            this.readBytes += nBytes;
        }

        // Start compression
        Future<?>[] futures = new Future<?>[go_upto];
        for(int i = 0; i < go_upto; i++)
        {
            futures[i] = (executor.submit(blocks[i]));
        }

        // End compression
        for(int i = 0; i < go_upto; i++)
        {
            futures[i].get();
        }      
        
        writer.writeAllBlocks();
        
        return blocks[go_upto];
    }

    public void compress() throws IOException, InterruptedException, ExecutionException
    {
        this.crc.reset();
        this.writeHeader();
        System.out.flush();

        ThreadPoolExecutor executor = (ThreadPoolExecutor) Executors.newFixedThreadPool(this.num_threads);

        // Contruct -> read -> compress -> write till done
        boolean complete = false;

        // Read in the first block (w/o running)
        BlockCompressor prevBlock = new BlockCompressor(0, this.crc, null, null);
        int firstBlockBytes = prevBlock.readInput();
        
        // This guards against empty files
        if(firstBlockBytes >= 0)
        {
            this.readBytes += firstBlockBytes;

            while(!complete)
            {
                prevBlock = compressBatch(executor, prevBlock);
                complete = prevBlock.nBytes < 0; 
            }         
            executor.shutdown();
        }
        else // Handle empty file
        {
            this.doEmptyFile();
        }

        this.writeTrailer((int)this.crc.getValue(), readBytes, 0);

        System.out.flush();
    }
}

////////////////////////////////////////////////////////////////////////////////////
// Pigzj
////////////////////////////////////////////////////////////////////////////////////
/*
    - Main class
    - Handles command line arguments
    - Call MultiThreadedCompressor to compress
*/

final public class Pigzj 
{
    private static  int num_threads;

    ////////////////////////////////////////////////////////////////////////////////////
    // Main
    ////////////////////////////////////////////////////////////////////////////////////

    public static void main (String[] args) throws FileNotFoundException, IOException, InterruptedException, ExecutionException
    {   
        ///////////////////////////////////////////////////////////////////////////////
        // Process command line arguments
        process_arguments(args);

        ///////////////////////////////////////////////////////////////////////////////
        // Compress
        MultiThreadedCompressor cmp = new MultiThreadedCompressor(num_threads);
        cmp.compress();
    }

    private static void process_arguments(String[] args)  
    {
        // Set default value for number of threads
        num_threads = Runtime.getRuntime().availableProcessors();

        // Parse options
        if(args.length == 2 && args[0].equals("-p")) // no. of threads provided
        {
            // Read the number 
            try
            { 
                int new_threads = Integer.parseInt(args[1]);
                if(new_threads > num_threads) // More requested than possible
                {
                    System.err.println("Error: -p processes more than supported by machine");
                    System.exit(1);                    
                }
                else
                {
                    num_threads = new_threads;
                }
            }
            catch (NumberFormatException e) // Could not read number
            {
                System.err.println("Error: -p processes must be an integer");
                System.exit(1);
            }
        }
        else if (args.length != 0)
        {
            System.err.println("Error: Invalid arguments");
            System.err.println("Usage: java Pigzj [-p PROCESSES]");
            System.exit(1);            
        }
    }
}