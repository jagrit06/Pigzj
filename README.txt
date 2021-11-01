Report

/////////////////////////////////////////////////////////
Section 0: Pigzj Structure
/////////////////////////////////////////////////////////

BlockingMap:
    - ConcurrentHashMap with block index as queue and BlockingQueues as value
    - Maintains queues of only size 1 for output buffers
    - When output buffer requested with a given index, queue[index].take() is called
        This blocks the method till output buffer of index is put into the BlockingMap
        Helps maintain order while writing output
    A similar strucutre was suggested on piazza: 
    https://stackoverflow.com/questions/23917818/concurrenthashmap-wait-for-key-possible
    Changes were made to make it closer to MessAdmin does with their BlockingQueue

Writer:
    - Handles the only shared variables between blocks 
    - Maintains BlockingMap between block index and output buffer
    - Maintains AtomicIntegerArray to store the amount of relevants 
        bytes in each output buffer (will beupdated in parallel) 
    - BlockCompressor can ask writer to note completed output buffer
        Adds them to BlockingMap with corresponding index
        (called in parallel)
    - Can write out all the blocks stored in BlockingMap and checks for errors 

BlockCompressor:
    - Runnable, run() is executed in parallel
    - Reads in 1 block from stdin
    - Updates crc while reading (so it is done in order)
    - Gets dictionary from previous block if exists
    - Has its own Deflater object
    - Compresses input in run() and sends output to be stored by writer

MultiThreadedCompressor:
    - Handles all overhead such as writing headers and trailers 
    - Makes the thread pool
    - Makes batches of num_threads blocks and compresses them in parallel
        * Each batch gets its own Writer
        * Each batch creates num_threads blocks and gets one block from the prev batch (for dict)
        * Each batch compresses num_threads blocks and passes its last block to the next batch
        * Compression happens by usung the threadpool 
        * Once compression is complete, output for the batch is written and we move to the next 

        Assuming we have 5 threads:

        Read by prev batch|            Read by this batch                   |   Read by next batch
            ... | Block 0 | Block 1 | Block 2 | Block 3 | Block 4 | Block 5 | ... 
                |             Compressed by this batch            |     Compressed by next batch

    The idea of reading in a batch at a time is inspired from pigz

Pigzj
    - Main class
    - Handles command line arguments
    - Call MultiThreadedCompressor to compress

/////////////////////////////////////////////////////////
Section 1: Testing set up
/////////////////////////////////////////////////////////

Linux server used: lnxsrv15
PATH starts with /usr/local/cs/bin
Java version: openjdk version "16.0.1"
pigz version: pigz 2.6
gzip version: gzip 1.10

Tests were conducted on a large file (java source modules), a medium file
(pigz source code) and a tiny file (small self made tiny file, which should 
just be one block) to compare time and space efficiency. The same tests were 
repeated for pigz and Pigzj with thread counts speicifed to 4 and 2. 

An empty file was tested as well as a sanity check to ensure all the basic
required overhead is coorectly inserted by all the 3 programs. The 
decompression output for Pigzj was also checked against the original input.

/////////////////////////////////////////////////////////
Section 2: Comparing Performance 
/////////////////////////////////////////////////////////
=========================================================
File name: /usr/local/cs/jdk-16.0.1/lib/modules
File size: 125942959
---------------------------------------------------------
gzip baseline:
    Trial 1:
        real    0m7.516s
        user    0m7.224s
        sys     0m0.181s

    Trial 2:
        real    0m7.578s
        user    0m7.211s
        sys     0m0.220s

    Trial 3:
        real    0m7.468s
        user    0m7.252s
        sys     0m0.135s

    gzip  compressed  size : 43261332
    gzip  compression ratio: .343
---------------------------------------------------------
pigz compression (with default thread count):
    Trial 1:
        real    0m2.168s
        user    0m7.008s
        sys     0m0.137s

    Trial 2:
        real    0m2.064s
        user    0m7.010s
        sys     0m0.138s

    Trial 3:
        real    0m2.330s
        user    0m7.016s
        sys     0m0.121s

    pigz  compressed  size : 43134815
    pigz  compression ratio: .342
---------------------------------------------------------
Pigzj compression (with default thread count):
    Trial 1:
        real    0m2.675s
        user    0m7.261s
        sys     0m0.461s

    Trial 2:
        real    0m2.676s
        user    0m7.226s
        sys     0m0.474s

    Trial 3:
        real    0m2.727s
        user    0m7.262s
        sys     0m0.509s

    Pigzj compressed  size : 43136276
    Pigzj compression ratio: .342
---------------------------------------------------------
pigz compression (with thread count 4):
    Trial 1:
        real    0m2.193s
        user    0m7.016s
        sys     0m0.123s

    Trial 2:
        real    0m2.062s
        user    0m7.005s
        sys     0m0.154s

    Trial 3:
        real    0m2.064s
        user    0m7.022s
        sys     0m0.117s

    pigz  compressed  size : 43134815
    pigz  compression ratio: .342
---------------------------------------------------------
Pigzj compression (with thread count 4):
    Trial 1:
        real    0m2.850s
        user    0m7.251s
        sys     0m0.496s

    Trial 2:
        real    0m2.722s
        user    0m7.216s
        sys     0m0.505s

    Trial 3:
        real    0m2.644s
        user    0m7.236s
        sys     0m0.473s

    Pigzj compressed  size : 43136276
    Pigzj compression ratio: .342
---------------------------------------------------------
pigz compression (with thread count 2):
    Trial 1:
        real    0m3.666s
        user    0m7.024s
        sys     0m0.128s

    Trial 2:
        real    0m3.662s
        user    0m6.944s
        sys     0m0.215s

    Trial 3:
        real    0m3.632s
        user    0m6.973s
        sys     0m0.215s

    pigz  compressed  size : 43134815
    pigz  compression ratio: .342
---------------------------------------------------------
Pigzj compression (with thread count 2):
    Trial 1:
        real    0m4.371s
        user    0m7.241s
        sys     0m0.469s

    Trial 2:
        real    0m4.374s
        user    0m7.319s
        sys     0m0.463s

    Trial 3:
        real    0m4.354s
        user    0m7.267s
        sys     0m0.477s

    Pigzj compressed  size : 43136276
    Pigzj compression ratio: .342
---------------------------------------------------------
Pigzj decompression successful!
=========================================================

=========================================================
File name: /usr/local/cs/src/pigz/pigz-2.6/pigz.c
File size: 178630
---------------------------------------------------------
gzip baseline:
    Trial 1:
        real    0m0.029s
        user    0m0.010s
        sys     0m0.001s

    Trial 2:
        real    0m0.014s
        user    0m0.009s
        sys     0m0.002s

    Trial 3:
        real    0m0.013s
        user    0m0.008s
        sys     0m0.003s

    gzip  compressed  size : 48175
    gzip  compression ratio: .269
---------------------------------------------------------
pigz compression (with default thread count):
    Trial 1:
        real    0m0.012s
        user    0m0.011s
        sys     0m0.001s

    Trial 2:
        real    0m0.011s
        user    0m0.011s
        sys     0m0.001s

    Trial 3:
        real    0m0.012s
        user    0m0.009s
        sys     0m0.003s

    pigz  compressed  size : 48260
    pigz  compression ratio: .270
    ---------------------------------------------------------
Pigzj compression (with default thread count):
    Trial 1:
        real    0m0.075s
        user    0m0.055s
        sys     0m0.029s

    Trial 2:
        real    0m0.080s
        user    0m0.056s
        sys     0m0.028s

    Trial 3:
        real    0m0.080s
        user    0m0.058s
        sys     0m0.027s

    Pigzj compressed  size : 48261
    Pigzj compression ratio: .270
---------------------------------------------------------
pigz compression (with thread count 4):
    Trial 1:
        real    0m0.012s
        user    0m0.010s
        sys     0m0.003s

    Trial 2:
        real    0m0.013s
        user    0m0.011s
        sys     0m0.001s

    Trial 3:
        real    0m0.017s
        user    0m0.011s
        sys     0m0.001s

    pigz  compressed  size : 48260
    pigz  compression ratio: .270
---------------------------------------------------------
Pigzj compression (with thread count 4):
    Trial 1:
        real    0m0.080s
        user    0m0.053s
        sys     0m0.031s

    Trial 2:
        real    0m0.078s
        user    0m0.055s
        sys     0m0.028s

    Trial 3:
        real    0m0.075s
        user    0m0.056s
        sys     0m0.028s

    Pigzj compressed  size : 48261
    Pigzj compression ratio: .270
---------------------------------------------------------
pigz compression (with thread count 2):
    Trial 1:
        real    0m0.012s
        user    0m0.009s
        sys     0m0.003s

    Trial 2:
        real    0m0.012s
        user    0m0.009s
        sys     0m0.003s

    Trial 3:
        real    0m0.012s
        user    0m0.011s
        sys     0m0.000s

    pigz  compressed  size : 48260
    pigz  compression ratio: .270
---------------------------------------------------------
Pigzj compression (with thread count 2):
    Trial 1:
        real    0m0.074s
        user    0m0.056s
        sys     0m0.027s

    Trial 2:
        real    0m0.076s
        user    0m0.059s
        sys     0m0.026s

    Trial 3:
        real    0m0.079s
        user    0m0.058s
        sys     0m0.029s

    Pigzj compressed  size : 48261
    Pigzj compression ratio: .270
---------------------------------------------------------
Pigzj decompression successful!
=========================================================

=========================================================
File name: tiny_file.txt
File size: 34
---------------------------------------------------------
gzip baseline:
    Trial 1:
        real    0m0.004s
        user    0m0.001s
        sys     0m0.000s

    Trial 2:
        real    0m0.003s
        user    0m0.000s
        sys     0m0.001s

    Trial 3:
        real    0m0.003s
        user    0m0.000s
        sys     0m0.001s

    gzip  compressed  size : 52
    gzip  compression ratio: 1.529
---------------------------------------------------------
pigz compression (with default thread count):
    Trial 1:
        real    0m0.005s
        user    0m0.000s
        sys     0m0.002s

    Trial 2:
        real    0m0.003s
        user    0m0.000s
        sys     0m0.001s

    Trial 3:
        real    0m0.003s
        user    0m0.000s
        sys     0m0.002s

    pigz  compressed  size : 52
    pigz  compression ratio: 1.529
---------------------------------------------------------
Pigzj compression (with default thread count):
    Trial 1:
        real    0m0.071s
        user    0m0.040s
        sys     0m0.031s

    Trial 2:
        real    0m0.072s
        user    0m0.046s
        sys     0m0.027s

    Trial 3:
        real    0m0.073s
        user    0m0.043s
        sys     0m0.030s

    Pigzj compressed  size : 52
    Pigzj compression ratio: 1.529
---------------------------------------------------------
pigz compression (with thread count 4):
    Trial 1:
        real    0m0.003s
        user    0m0.001s
        sys     0m0.001s

    Trial 2:
        real    0m0.004s
        user    0m0.000s
        sys     0m0.002s

    Trial 3:
        real    0m0.003s
        user    0m0.001s
        sys     0m0.000s

    pigz  compressed  size : 52
    pigz  compression ratio: 1.529
---------------------------------------------------------
Pigzj compression (with thread count 4):
    Trial 1:
        real    0m0.071s
        user    0m0.042s
        sys     0m0.030s

    Trial 2:
        real    0m0.073s
        user    0m0.047s
        sys     0m0.026s

    Trial 3:
        real    0m0.073s
        user    0m0.050s
        sys     0m0.024s

    Pigzj compressed  size : 52
    Pigzj compression ratio: 1.529
---------------------------------------------------------
pigz compression (with thread count 2):
    Trial 1:
        real    0m0.005s
        user    0m0.001s
        sys     0m0.001s

    Trial 2:
        real    0m0.007s
        user    0m0.000s
        sys     0m0.002s

    Trial 3:
        real    0m0.004s
        user    0m0.000s
        sys     0m0.001s

    pigz  compressed  size : 52
    pigz  compression ratio: 1.529
---------------------------------------------------------
Pigzj compression (with thread count 2):
    Trial 1:
        real    0m0.075s
        user    0m0.046s
        sys     0m0.026s

    Trial 2:
        real    0m0.074s
        user    0m0.046s
        sys     0m0.026s

    Trial 3:
        real    0m0.082s
        user    0m0.048s
        sys     0m0.024s

    Pigzj compressed  size : 52
    Pigzj compression ratio: 1.529
---------------------------------------------------------
Pigzj decompression successful!
=========================================================

=========================================================
File name: empty.txt
File size: 0
---------------------------------------------------------
gzip baseline:
    Trial:
        real    0m0.003s
        user    0m0.000s
        sys     0m0.001s

    gzip  compressed  size : 20
---------------------------------------------------------
pigz compression (with default thread count):
    Trial:
        real    0m0.004s
        user    0m0.000s
        sys     0m0.001s


    pigz  compressed  size : 20
---------------------------------------------------------
Pigzj compression (with default thread count):
    Trial:
        real    0m0.056s
        user    0m0.024s
        sys     0m0.028s

    Pigzj compressed  size : 20
---------------------------------------------------------
Pigzj decompression successful!
=========================================================

Comparing Times:

The performance of Pigzj is closer to that of pigz than gzip on the large files
as required. For larger sized files and many threads, pigz compresses the fatest, 
with Pigzj close behind and gzip taking the longest. The parallel programs 
however suffer in smaller files.

Looking at the number of threads, as expected, pigz and Pigzj get slower with a 
lower number of threads, and suffer. Pigzj is slower than pigz on tiny files
with a low no. of threads, but in such situations, gzip maybe better suited.

=========================================================

Comparing Sizes:

The size differences between the compressed outputs of Pigzj and pigz are not 
siginificant. Like the spec requires, the output  of Pigzj is closer to the 
output of pigz than gzip. The compression ratios between Pigzj and pigz on all 
files (up to a precision of 3 decimals) are the same.  

The compressed outputs of Pigzj and pigz are smaller than the output of gzip 
(becuase both use pigz's ditionary trick) for larger files. For smaller files, 
gzip gives better compresion (which is to be expected, pigz and Pigzj are 
designed for larger files). 

A difference in number of threads does not affect the size of the compressed output
which is the same for each run on a given file, which is expected behavior.

=========================================================

/////////////////////////////////////////////////////////
Section 3: Understanding the differences
/////////////////////////////////////////////////////////

strace was run on the 3 programs compressing the large file
(since that is where the differences were most pronounced). 

Note: The summaries presented are with the wall clock time flag speicifed. 

=========================================================
$ strace -cw gzip <$input >gzip.gz

% time     seconds  usecs/call     calls    errors syscall
------ ----------- ----------- --------- --------- ----------------
 52.58    0.067481          25      2641           write
 45.36    0.058225          15      3846           read
  1.21    0.001547         386         4           close
  0.40    0.000510         509         1           execve
  0.15    0.000192          16        12           rt_sigaction
  0.07    0.000091          18         5           mmap
  0.06    0.000081          20         4           mprotect
  0.04    0.000054          18         3           fstat
  0.03    0.000037          18         2           openat
  0.03    0.000032          16         2         1 arch_prctl
  0.02    0.000030          30         1           munmap
  0.02    0.000019          19         1         1 access
  0.01    0.000016          16         1         1 ioctl
  0.01    0.000016          16         1           brk
  0.01    0.000016          15         1           lseek
------ ----------- ----------- --------- --------- ----------------
100.00    0.128350                  6525         3 total

=========================================================
$ strace -cw pigz <$input >pigz.gz

% time     seconds  usecs/call     calls    errors syscall
------ ----------- ----------- --------- --------- ----------------
 97.17    1.886980        2966       636         2 futex
  2.68    0.052047          53       971           read
  0.04    0.000765          34        22           munmap
  0.03    0.000523         522         1           execve
  0.02    0.000447          15        28           mmap
  0.01    0.000281          18        15           mprotect
  0.01    0.000157          31         5           clone
  0.01    0.000128          21         6           close
  0.01    0.000125          20         6           openat
  0.01    0.000112          13         8           brk
  0.01    0.000101          16         6           fstat
  0.00    0.000048          16         3           rt_sigaction
  0.00    0.000048          15         3           lseek
  0.00    0.000032          16         2         2 ioctl
  0.00    0.000032          16         2         1 arch_prctl
  0.00    0.000020          19         1         1 access
  0.00    0.000016          16         1           prlimit64
  0.00    0.000016          16         1           set_robust_list
  0.00    0.000016          15         1           set_tid_address
  0.00    0.000016          15         1           rt_sigprocmask
------ ----------- ----------- --------- --------- ----------------
100.00    1.941908                  1719         6 total

=========================================================
$ strace -cw java Pigzj <$input >Pigzj.gz

% time     seconds  usecs/call     calls    errors syscall
------ ----------- ----------- --------- --------- ----------------
 99.80    2.576649     1288324         2           futex
  0.07    0.001738          35        49        39 openat
  0.04    0.001000          30        33        30 stat
  0.02    0.000506         505         1           execve
  0.02    0.000429          18        23           mmap
  0.01    0.000300          19        15           mprotect
  0.01    0.000205          17        12           read
  0.01    0.000202          18        11           close
  0.01    0.000175          17        10           fstat
  0.01    0.000131          43         3           munmap
  0.00    0.000065          16         4           brk
  0.00    0.000056          28         2           readlink
  0.00    0.000049          16         3           lseek
  0.00    0.000039          19         2         1 access
  0.00    0.000036          36         1           clone
  0.00    0.000032          16         2           rt_sigaction
  0.00    0.000032          16         2         1 arch_prctl
  0.00    0.000016          16         1           rt_sigprocmask
  0.00    0.000016          16         1           prlimit64
  0.00    0.000016          16         1           getpid
  0.00    0.000016          16         1           set_robust_list
  0.00    0.000016          15         1           set_tid_address
------ ----------- ----------- --------- --------- ----------------
100.00    2.581726                   180        71 total

=========================================================

From these results, the differences in performances are apparent:

  - gzip spends most of its time in the user level where the compression occurs
    and mostly switches modes for (relatively frequent) read and write calls. 
    Most of its time is spend in it single threadedly compressing the data.

  - pigz spends a siginificant amount of system call time on a futex lock
    which is to be expected since it handles multithreading. It also spends 
    a more time than Pigzj on read call (possibly because how read buffering
    differ in Java interpreter/gcc)

  - Pigzj spends a very high amount of its system call time on futex locks. 
    This probably corresponds to the use of data structures such as 
    AtomicIntegerArray, BlockQueue and ConcurrentHashMaps, and also since 
    Pigzj forces threads to finsih their compression tasks before writing 
    the compressed buffers to stdout. It spends less time in other system 
    calls such as read (as above, possibly becuase of the stdin buffering)

In net, both Pigzj and pigz spend a large amount of time in lock, which indicates
the similarity in their structures. Further, the fact that Pigzj spends more time
with a lock (by about 0.5-0.6s for this particular file) matches exactly with it 
taking more time to finish up the compression (it is usually slower by 0.6s). 

=========================================================

I suspect that slight difference in the compressed outputs comes from the 
compression flags I used in the Deflater (SYNC_FLUSH), which was the same as 
the given starter code. Since it give almost similar results and could 
successfully be decompressed by pigz for inputs of different sizes, that 
wasn't changed. 

=========================================================

/////////////////////////////////////////////////////////
Section 4: Problems in scaling Pigzj
/////////////////////////////////////////////////////////

In its current form, the Pigzj implementation should be able to handle larger files 
at good speeds (even if not as fast as pigz, it will be much faster than gzip). 
This is because we only have atmost num_threads + 1 block in memory, which should
be viable for most machines. 

Increasing the number of threads dramatically could add addtional time Pigzj spends 
in locks which could slow it down since for every batch of num_threads blocks, 
Pigzj must wait till each thread is done with its tasks before writing out the output
and only then can it read the next blocks for compression (pigz does similar). 
It could be useful for very big files where there are enough batches to justify the 
overhead costs of the many threads and handling them. 

It is however to be noted that the Pigzj will be more secure than pigz and portable
on different machines (which is why we wrote in Java in the first place). So, if
sacirificing just a little performance is viable, Pigzj would be the right choice for 
larger files on a system with multiple cores. 

/////////////////////////////////////////////////////////
Section 5: References
/////////////////////////////////////////////////////////

1. TA slides
2. MessAdmin: https://github.com/MessAdmin
3. pigz: https://github.com/madler/pigz
4. Threadpool tutorial: http://tutorials.jenkov.com/java-concurrency/thread-pools.html
5. Java docs
6. All the very helpful posts on piazza
