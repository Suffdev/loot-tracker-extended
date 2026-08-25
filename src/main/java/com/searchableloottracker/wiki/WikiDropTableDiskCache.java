package com.searchableloottracker.wiki;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Comparator;
import lombok.extern.slf4j.Slf4j;

/**
 * Versioned, bounded, best-effort disk cache for parsed Wiki tables. Filenames
 * are hashes of lookup keys, so untrusted source names never become paths.
 */
@Slf4j
final class WikiDropTableDiskCache
{
	private static final int MAGIC = 0x4c544557;
	private static final int VERSION = 1;
	private static final String EXTENSION = ".table";

	private final File directory;
	private final int maximumEntries;
	private long lastAccessTimestamp;

	WikiDropTableDiskCache(File directory, int maximumEntries)
	{
		this.directory = directory;
		this.maximumEntries = maximumEntries;
	}

	synchronized WikiDropTable get(String key)
	{
		if (directory == null)
		{
			return null;
		}
		File file = fileFor(key);
		if (!file.isFile())
		{
			return null;
		}
		try (DataInputStream input = new DataInputStream(
			new BufferedInputStream(new FileInputStream(file))))
		{
			if (input.readInt() != MAGIC || input.readInt() != VERSION)
			{
				throw new IOException("Unsupported cache format");
			}
			WikiDropTable table = WikiDropTable.read(input);
			file.setLastModified(nextAccessTimestamp());
			return table;
		}
		catch (IOException | RuntimeException exception)
		{
			log.debug("Unable to read cached Wiki drop table", exception);
			if (!file.delete())
			{
				log.debug("Unable to remove invalid Wiki drop table cache file {}", file);
			}
			return null;
		}
	}

	synchronized void put(String key, WikiDropTable table)
	{
		if (directory == null || maximumEntries <= 0 || (!directory.isDirectory() && !directory.mkdirs()))
		{
			return;
		}
		File target = fileFor(key);
		File temporary = new File(directory, target.getName() + ".tmp");
		try (DataOutputStream output = new DataOutputStream(
			new BufferedOutputStream(new FileOutputStream(temporary))))
		{
			output.writeInt(MAGIC);
			output.writeInt(VERSION);
			table.write(output);
		}
		catch (IOException exception)
		{
			log.debug("Unable to write cached Wiki drop table", exception);
			temporary.delete();
			return;
		}

		try
		{
			// Install only a complete serialized table. A failed write leaves no
			// partially readable target entry behind.
			Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
			target.setLastModified(nextAccessTimestamp());
			prune();
		}
		catch (IOException exception)
		{
			log.debug("Unable to install cached Wiki drop table", exception);
			temporary.delete();
		}
	}

	synchronized void clear()
	{
		if (directory == null)
		{
			return;
		}
		File[] files = directory.listFiles((ignored, name) ->
			name.endsWith(EXTENSION) || name.endsWith(EXTENSION + ".tmp"));
		if (files == null)
		{
			return;
		}
		for (File file : files)
		{
			if (!file.delete())
			{
				log.debug("Unable to remove Wiki drop table cache file {}", file);
			}
		}
	}

	private void prune()
	{
		File[] files = directory.listFiles((ignored, name) -> name.endsWith(EXTENSION));
		if (files == null || files.length <= maximumEntries)
		{
			return;
		}
		Arrays.sort(files, Comparator.comparingLong(File::lastModified));
		for (int index = 0; index < files.length - maximumEntries; index++)
		{
			if (!files[index].delete())
			{
				log.debug("Unable to prune Wiki drop table cache file {}", files[index]);
			}
		}
	}

	private File fileFor(String key)
	{
		return new File(directory, sha256(key) + EXTENSION);
	}

	private long nextAccessTimestamp()
	{
		lastAccessTimestamp = Math.max(System.currentTimeMillis(), lastAccessTimestamp + 1);
		return lastAccessTimestamp;
	}

	private static String sha256(String value)
	{
		try
		{
			byte[] digest = MessageDigest.getInstance("SHA-256")
				.digest(value.getBytes(StandardCharsets.UTF_8));
			StringBuilder encoded = new StringBuilder(digest.length * 2);
			for (byte part : digest)
			{
				encoded.append(String.format("%02x", part & 0xff));
			}
			return encoded.toString();
		}
		catch (NoSuchAlgorithmException exception)
		{
			throw new IllegalStateException("SHA-256 is unavailable", exception);
		}
	}
}
