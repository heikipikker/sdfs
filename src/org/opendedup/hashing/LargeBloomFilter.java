package org.opendedup.hashing;

import java.io.File;

import java.io.IOException;
import java.io.Serializable;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.opendedup.logging.SDFSLogger;
import org.opendedup.sdfs.Main;
import org.opendedup.sdfs.io.WritableCacheBuffer.BlockPolicy;
import org.opendedup.util.CommandLineProgressBar;

public class LargeBloomFilter implements Serializable {

	/**
	 * 
	 */
	private static final long serialVersionUID = 1L;
	private static final int AR_SZ = 256;
	transient FLBF[] bfs = new FLBF[AR_SZ];
	private transient RejectedExecutionHandler executionHandler = new BlockPolicy();
	private transient SynchronousQueue<Runnable> worksQueue = new SynchronousQueue<Runnable>();

	private transient ThreadPoolExecutor executor = null;

	public LargeBloomFilter() {

	}

	public LargeBloomFilter(FLBF[] bfs) {
		this.bfs = bfs;
	}

	public static boolean exists(File dir) {
		for (int i = 0; i < AR_SZ; i++) {
			File f = new File(dir.getPath() + File.separator + "lbf" + i
					+ ".nbf");
			if (!f.exists())
				return false;

		}
		return true;
	}

	public LargeBloomFilter(File dir, long sz, double fpp, boolean fb,
			boolean sync) throws IOException {
		executor = new ThreadPoolExecutor(Main.writeThreads, Main.writeThreads,
				10, TimeUnit.SECONDS, worksQueue, executionHandler);

		bfs = new FLBF[AR_SZ];
		CommandLineProgressBar bar = null;
		if (fb)
			bar = new CommandLineProgressBar("Loading BloomFilters",
					bfs.length, System.out);
		int isz = (int) (sz / bfs.length);
		for (int i = 0; i < bfs.length; i++) {
			File f = new File(dir.getPath() + File.separator + "lbf" + i
					+ ".nbf");
			FBLoader th = new FBLoader();
			th.bfs = bfs;
			th.pos = i;
			th.f = f;
			th.sync = sync;
			th.fpp = fpp;
			th.sz = isz;
			executor.execute(th);
			//bfs[i] = new FLBF(isz, fpp, f, sync);
			if (bar != null)
				bar.update(i);

		}
		executor.shutdown();
		if (fb) {
			bar.finish();
			System.out.println("Waiting for last bloomfilters to load");
		}
		try {
			while (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
				SDFSLogger.getLog().debug(
						"Awaiting bloomload completion of threads.");
				if (fb) {
					System.out.print(".");
				}

			}
		} catch (Exception e) {
			SDFSLogger.getLog().error("error waiting for thermination", e);
		}
	}

	private FLBF getMap(byte[] hash) {

		int hashb = hash[1];
		if (hashb < 0) {
			hashb = ((hashb * -1) + 127);
		}
		FLBF m = bfs[hashb];
		return m;
	}

	public boolean mightContain(byte[] b) {
		return getMap(b).mightContain(b);
	}

	public void put(byte[] b) {
		getMap(b).put(b);
	}

	public void save(File dir) throws IOException {
		CommandLineProgressBar bar = new CommandLineProgressBar(
				"Saving BloomFilters", bfs.length, System.out);
		executor = new ThreadPoolExecutor(Main.writeThreads, Main.writeThreads,
				100, TimeUnit.SECONDS, worksQueue, executionHandler);
		for (int i = 0; i < bfs.length; i++) {
			FBSaver th = new FBSaver();
			th.pos = i;
			th.bfs = bfs;
			executor.execute(th);
			bar.update(i);
		}
		executor.shutdown();
		System.out.println("Waiting for last bloomfilters to save");
		try {
			while (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
				SDFSLogger.getLog().debug(
						"Awaiting bloomfilter to finish save.");
				System.out.print(".");
			}
		} catch (Exception e) {
			SDFSLogger.getLog().error("error waiting for thermination", e);
		}
		bar.finish();
	}

	public FLBF[] getArray() {
		return this.bfs;
	}

	public static void main(String[] args) {
		int[] ht = new int[32];
		for (int i = 0; i < 128; i++) {
			int z = i / 4;
			ht[z]++;
		}
		for (int i : ht) {
			System.out.println("i=" + i);
		}
	}

	private static class FBLoader implements Runnable {
		transient FLBF[] bfs = null;
		int pos;
		File f;
		long sz;
		double fpp;
		boolean sync;

		@Override
		public void run() {
			bfs[pos] = new FLBF(sz, fpp, f, sync);

		}

	}

	private static class FBSaver implements Runnable {
		transient FLBF[] bfs = null;
		int pos;

		@Override
		public void run() {
			try {
				bfs[pos].save();
			} catch (IOException e1) {
				SDFSLogger.getLog().error("unable to save", e1);
			}

		}

	}

}
