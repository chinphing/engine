package com.alibabacloud.polar_race.engine.common;

import com.alibabacloud.polar_race.engine.common.exceptions.EngineException;
import com.alibabacloud.polar_race.engine.common.exceptions.RetCodeEnum;
import io.netty.util.concurrent.FastThreadLocal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

public class EngineRace extends AbstractEngine {

    private static Logger logger = LoggerFactory.getLogger(EngineRace.class);

    private static final int KEY_LEN = 8;
    //总key数量
    private static final int KEY_NUM = 64000000;
    //    private static final int KEY_NUM = 64000;
    // key+offset 长度 16B
    private static final int KEY_AND_OFF_LEN = 12;
    // 线程数量
    private static final int THREAD_NUM = 64;
    // value 长度 4K
    private static final int VALUE_LEN = 4096;

    private static final int SHIFT_NUM = 12;
    // 存放 value 的文件数量 128
    private static final int FILE_COUNT = 256;

    //128块 1块 8.4375m = 8640 KB = 8847360 B  1个文件 1080m
    private static final int FILE_SIZE = 1132462080;

    private static final int BLOCK_NUM = 128;

    private static final int BLOCK_SIZE = 8847360;
    // BLOCK_SIZE / VALUE_LEN
    private static final int MAX_NUM_PER_BLOCK = 2160;

    private static final int HASH_VALUE = 0x3F;
    // 第i个key
    private static final long[] keys = new long[KEY_NUM];
    // 第i个key的对应value的索引
    private static final int[] offs = new int[KEY_NUM];


    //key 文件的fileChannel
    private static FileChannel[] keyFileChannels = new FileChannel[THREAD_NUM];

    private static AtomicInteger[] keyOffsets = new AtomicInteger[THREAD_NUM];

//    private static MappedByteBuffer[] keyMappedByteBuffers = new MappedByteBuffer[THREAD_NUM];

    private static MappedByteBuffer[] valueMappedByteBuffers = new MappedByteBuffer[FILE_COUNT];

    //value 文件的fileChannel
    private static FileChannel[] fileChannels = new FileChannel[FILE_COUNT];
    //每个valueOffsets表示的都是第i个文件中的value数量
    private static AtomicInteger[][] valueOffsets = new AtomicInteger[FILE_COUNT][BLOCK_NUM];

    private static FastThreadLocal<ByteBuffer> localBufferKey = new FastThreadLocal<ByteBuffer>() {
        @Override
        protected ByteBuffer initialValue() throws Exception {
            return ByteBuffer.allocate(KEY_AND_OFF_LEN);
        }
    };

    private static FastThreadLocal<ByteBuffer> localBufferValue = new FastThreadLocal<ByteBuffer>() {
        @Override
        protected ByteBuffer initialValue() throws Exception {
            return ByteBuffer.allocate(VALUE_LEN);
        }
    };

    private static FastThreadLocal<byte[]> localKeyBytes = new FastThreadLocal<byte[]>() {
        @Override
        protected byte[] initialValue() throws Exception {
            return new byte[KEY_LEN];
        }
    };

    private static FastThreadLocal<byte[]> localValueBytes = new FastThreadLocal<byte[]>() {
        @Override
        protected byte[] initialValue() throws Exception {
            return new byte[VALUE_LEN];
        }
    };

    private static FastThreadLocal<ByteBuffer> localBlockBuffer = new FastThreadLocal<ByteBuffer>() {
        @Override
        protected ByteBuffer initialValue() throws Exception {
            return ByteBuffer.allocate(BLOCK_SIZE);
        }
    };

    private int CURRENT_KEY_NUM;

    @Override
    public void open(String path) throws EngineException {
        logger.info("--------open--------");
        File file = new File(path);
        // 创建目录
        if (!file.exists()) {
            if (!file.mkdir()) {
                throw new EngineException(RetCodeEnum.IO_ERROR, "创建文件目录失败：" + path);
            } else {
                logger.info("创建文件目录成功：" + path);
            }
        }
        RandomAccessFile randomAccessFile;
        // file是一个目录时进行接下来的操作
        if (file.isDirectory()) {
            try {
                //先构建keyFileChannel 和 初始化 map
                for (int i = 0; i < THREAD_NUM; i++) {
                    randomAccessFile = new RandomAccessFile(path + File.separator + i + ".key", "rw");
                    FileChannel channel = randomAccessFile.getChannel();
                    keyFileChannels[i] = channel;
                    keyOffsets[i] = new AtomicInteger((int) channel.size());
                }
                CountDownLatch countDownLatch = new CountDownLatch(THREAD_NUM);
                CURRENT_KEY_NUM = 0;
                for (int i = 0; i < THREAD_NUM; i++) {
                    if (!(keyOffsets[i].get() == 0)) {
                        final long off = keyOffsets[i].get();
                        // 第i个文件写入 keys 的起始位置
                        final int temp = CURRENT_KEY_NUM;
                        CURRENT_KEY_NUM += off / 12;
                        final MappedByteBuffer buffer = keyFileChannels[i].map(FileChannel.MapMode.READ_ONLY, 0, keyOffsets[i].get());
                        new Thread(() -> {
                            int start = 0;
                            int n = temp;
                            while (start < off) {
                                start += KEY_AND_OFF_LEN;
                                keys[n] = buffer.getLong();
                                offs[n++] = buffer.getInt();
                            }
                            countDownLatch.countDown();
                        }).start();
                    } else {
                        countDownLatch.countDown();
                    }
                }
                countDownLatch.await();
                //获取完之后对key进行排序
                long sortStartTime = System.currentTimeMillis();
                heapSort(CURRENT_KEY_NUM);
                long sortEndTime = System.currentTimeMillis();
                logger.info("sort 耗时 " + (sortEndTime - sortStartTime) + "ms");
                logger.info("CURRENT_KEY_NUM = " + CURRENT_KEY_NUM);
                CURRENT_KEY_NUM = handleDuplicate(CURRENT_KEY_NUM);
                logger.info("handleDuplicate 耗时" + (System.currentTimeMillis() - sortEndTime) + "ms");
                logger.info("CURRENT_KEY_NUM is " + CURRENT_KEY_NUM + " after handle duplicate");
                //创建 FILE_COUNT个FileChannel 分块写入
                for (int i = 0; i < FILE_COUNT; i++) {
                    try {
                        randomAccessFile = new RandomAccessFile(path + File.separator + i + ".data", "rw");
                        FileChannel channel = randomAccessFile.getChannel();
                        fileChannels[i] = channel;
                        // 从 length处直接写入
                        valueMappedByteBuffers[i] = channel.map(FileChannel.MapMode.READ_WRITE, 0, FILE_SIZE);
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
                for (int i = 0; i < FILE_COUNT; i++) {
                    for (int j = 0; j < BLOCK_NUM; j++) {
                        valueOffsets[i][j] = new AtomicInteger(0);
                    }
                }
            } catch (IOException | InterruptedException e) {
                e.printStackTrace();
            }
        } else {
            throw new EngineException(RetCodeEnum.IO_ERROR, "path不是一个目录");
        }
    }

    private int handleDuplicate(int keyNum) {
        int maxnum = 1;
        for (int i = 1; i < keyNum; ++i) {
            if (keys[i] != keys[i - 1]) {
                keys[maxnum] = keys[i];
                offs[maxnum] = offs[i];
                maxnum++;
            }
        }
        return maxnum;
    }

    @Override
    public void write(byte[] key, byte[] value) throws EngineException {
        long numkey = Util.bytes2long(key);
        int keyHash = keyFileHash(numkey);
        int blockHash = valueBlockHash(numkey);
        int fileHash = valueFileHash(numkey);
        // value 写入的 offset，每个块内单独计算off
        int off = valueOffsets[fileHash][blockHash].getAndIncrement();
        try {
            ByteBuffer keyBuffer = localBufferKey.get();
            keyBuffer.putLong(numkey).putInt(off);
            keyBuffer.flip();
            keyFileChannels[keyHash].write(keyBuffer, keyOffsets[keyHash].getAndAdd(KEY_AND_OFF_LEN));
            keyBuffer.clear();
            //将value写入buffer
            ByteBuffer valueBuffer = valueMappedByteBuffers[fileHash].slice();
            valueBuffer.position((blockHash * BLOCK_SIZE) + (off << SHIFT_NUM));
            valueBuffer.put(value);
        } catch (IOException e) {
            throw new EngineException(RetCodeEnum.IO_ERROR, "写入数据出错");
        }
    }


    @Override
    public byte[] read(byte[] key) throws EngineException {
        long numkey = Util.bytes2long(key);
        int fileHash = valueFileHash(numkey), blockHash = valueBlockHash(numkey);
        int off = getKey(numkey);
        if (off == -1) {
            throw new EngineException(RetCodeEnum.NOT_FOUND, numkey + "不存在");
        }
        ByteBuffer buffer = valueMappedByteBuffers[fileHash].slice();
        buffer.position((blockHash * BLOCK_SIZE) + (off << SHIFT_NUM));
        buffer.get(localValueBytes.get(), 0, VALUE_LEN);
        return localValueBytes.get();
    }


    @Override
    public void range(byte[] lower, byte[] upper, AbstractVisitor visitor) throws EngineException {
        long key;
        int hash, blockHash;
        byte[] keyBytes = localKeyBytes.get();
        byte[] valueBytes = localValueBytes.get();
        logger.info("CURRENT_KEY_NUM = " + CURRENT_KEY_NUM);
        ByteBuffer blockBuffer = localBlockBuffer.get();
        if ((lower == null || lower.length < 1) && (upper == null || upper.length < 1)) {
            try {
                for (int i = 0; i < CURRENT_KEY_NUM; ++i) {
//                    while (i + 1 < CURRENT_KEY_NUM && keys[i] == keys[i + 1]) {
//                        ++i;
//                    }
                    key = keys[i];
                    blockHash = valueBlockHash(key);
                    hash = valueFileHash(key);
                    blockBuffer.clear();
                    fileChannels[hash].read(blockBuffer, fileChannels[hash].read(blockBuffer, blockHash * BLOCK_SIZE));
                    while (i < CURRENT_KEY_NUM && valueFileHash(keys[i]) == hash && valueBlockHash(keys[i]) == blockHash) {
                        blockBuffer.get(valueBytes, offs[i] << SHIFT_NUM, VALUE_LEN);
                        long2bytes(keyBytes, key);
                        visitor.visit(keyBytes, valueBytes);
                        ++i;
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
                throw new EngineException(RetCodeEnum.IO_ERROR, "range read io 出错");
            }
        } else {
            throw new EngineException(RetCodeEnum.NOT_SUPPORTED, "range传入的lower，upper 不为空");
        }
    }

    private void long2bytes(byte[] bytes, long key) {
        for (int i = 7; i >= 0; i--) {
            bytes[i] = (byte) (key & 0xFF);
            key >>= 8;
        }
    }


    @Override
    public void close() {
        logger.info("--------close--------");
        for (int i = 0; i < FILE_COUNT; i++) {
            try {
//                logger.info("file" + i + " size is " + valueOffsets[i].get());
                keyFileChannels[i].close();
                fileChannels[i].close();
            } catch (IOException e) {
                logger.error("close error");
            }
        }
    }

    private static int keyFileHash(long key) {
        //取前8位，分为256个文件
        return (int) (key >>> 58);
//        return (int) (key & HASH_VALUE);
    }

    private static int valueFileHash(long key) {
        //取前8位，分为256个文件
        return (int) (key >>> 56);
//        return (int) (key & HASH_VALUE);
    }

    // 分128个block
    private static int valueBlockHash(long key) {
        return (int) ((key >>> 49) & 0x3F);
//        return (int) (key & HASH_VALUE);
    }

    private int getKey(long numkey) {
        int l = 0, r = CURRENT_KEY_NUM - 1, mid;
        long num;
        while (l <= r) {
            mid = (l + r) >> 1;
            num = keys[mid];
            if (num < numkey) {
                l = mid + 1;
            } else if (num > numkey) {
                r = mid - 1;
            } else {
                return offs[mid];
            }
        }
        return -1;
    }

    /**
     * 对 index数组进行堆排
     *
     * @param startKeyNum 只排序前startKeyNum个数字
     */
    private void heapSort(int startKeyNum) {
        int end = startKeyNum - 1;
        for (int i = end >> 1; i >= 0; --i) {
            shiftDown(end, i);
        }
        for (int keyNum = end; keyNum > 0; --keyNum) {
            swap(keyNum, 0);
            shiftDown(keyNum - 1, 0);
        }
    }

    /**
     * index是从 0 开始的数组，则 k *2 之后要 + 1
     *
     * @param end 待排序的数组末尾
     * @param k   待shiftDown的位置
     */
    private void shiftDown(int end, int k) {
        int j = (k << 1) + 1;
        while (j <= end) {
            // 比较的数字是 index对应的key
            if (j + 1 <= end && (keys[j] < keys[j + 1] || (keys[j] == keys[j + 1] && offs[j] > offs[j + 1]))) {
                ++j;
            }
            if (keys[k] > keys[j] || (keys[k] == keys[j] && offs[k] < offs[j])) {
                break;
            }
            swap(k, j);
            k = j;
            j = (k << 1) + 1;
        }
    }

    private void swap(int i, int j) {
        long temp = keys[i];
        keys[i] = keys[j];
        keys[j] = temp;
        temp = offs[i];
        offs[i] = offs[j];
        offs[j] = (int) temp;
    }

}
