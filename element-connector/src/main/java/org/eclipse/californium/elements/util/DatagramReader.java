/*******************************************************************************
 * Copyright (c) 2015 Institute for Pervasive Computing, ETH Zurich and others.
 * 
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v2.0
 * and Eclipse Distribution License v1.0 which accompany this distribution.
 * 
 * The Eclipse Public License is available at
 *    http://www.eclipse.org/legal/epl-v20.html
 * and the Eclipse Distribution License is available at
 *    http://www.eclipse.org/org/documents/edl-v10.html.
 * 
 * Contributors:
 *    Matthias Kovatsch - creator and main architect
 *    Stefan Jucker - DTLS implementation
 *    Achim Kraus (Bosch Software Innovations GmbH) - add "mark" and "reset"
 *    Achim Kraus (Bosch Software Innovations GmbH) - add constructor without 
 *                                                    cloning of the provided data.
 ******************************************************************************/
package org.eclipse.californium.elements.util;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.spec.AlgorithmParameterSpec;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;

/**
 * This class describes the functionality to read raw network-ordered datagrams
 * on bit-level.
 */
public final class DatagramReader {

	/**
	 * Input stream with improved range reading.
	 */
	private static class RangeInputStream extends ByteArrayInputStream {

		/**
		 * Create reader from byte array.
		 * 
		 * @param buffer directly used byte array
		 */
		private RangeInputStream(byte[] buffer) {
			super(buffer);
		}

		/**
		 * Create reader from byte array range.
		 * 
		 * @param buffer directly used byte array
		 * @param offset offset in buffer
		 * @param length length of range
		 */
		private RangeInputStream(byte[] buffer, int offset, int length) {
			super(buffer, offset, length);
		}

		/**
		 * Create reader for range. Read range and pass it to the returned
		 * reader.
		 * 
		 * @param count number of bytes for the range
		 * @return reader containing the range
		 * @throws IllegalArgumentException if provided count exceeds available bytes
		 */
		private RangeInputStream range(int count) {
			int offset = pos;
			long available = skip(count);
			if (available < count) {
				throw new IllegalArgumentException(
						"requested " + count + " bytes exceeds available " + available + " bytes.");
			}
			return new RangeInputStream(buf, offset, count);
		}

		private void decrypt(Cipher cipher, AlgorithmParameterSpec parameterSpec, SecretKey key)
				throws GeneralSecurityException {
			cipher.init(Cipher.DECRYPT_MODE, key, parameterSpec);
			count = cipher.doFinal(buf, pos, available(), buf, pos) + pos;
		}

		private void updateMessageDigest( MessageDigest md) {
			md.update(buf, pos, available());
		}

	}
	// Attributes //////////////////////////////////////////////////////////////

	private final InputStream byteStream;

	private byte currentByte;
	private int currentBitIndex;

	/**
	 * Copy of {@link #currentByte}, when {@link #mark()} is called.
	 */
	private byte markByte;
	/**
	 * Copy of {@link #currentBitIndex}, when {@link #mark()} is called.
	 */
	private int markBitIndex;

	// Constructors ////////////////////////////////////////////////////////////

	/**
	 * Creates a new reader for an copied array of bytes.
	 * 
	 * @param byteArray The byte array to read from.
	 */
	public DatagramReader(final byte[] byteArray) {
		this(byteArray, true);
	}

	/**
	 * Creates a new reader for an array of bytes.
	 * 
	 * @param byteArray The byte array to read from.
	 * @param copy {@code true} to copy the array, {@code false} to us it
	 *            directly.
	 */
	public DatagramReader(final byte[] byteArray, boolean copy) {
		this(copy ? Arrays.copyOf(byteArray, byteArray.length) : byteArray, 0, byteArray.length);
	}

	/**
	 * Creates a new reader for a range within an array of bytes.
	 * 
	 * The array is used directly and is not copied. If a copy is required, copy
	 * the range and provide that to {@link #DatagramReader(byte[])}.
	 * 
	 * @param byteArray The byte array to read from.
	 * @param offset starting offset of the range.
	 * @param length length of the range.
	 * 
	 * @since 2.4
	 */
	public DatagramReader(final byte[] byteArray, int offset, int length) {
		this(new RangeInputStream(byteArray, offset, length));
	}

	/**
	 * Creates a new reader for an bytes stream.
	 * 
	 * @param byteStream The byte stream to read from.
	 * @throws NullPointerException if byte stream is {@code null}
	 */
	public DatagramReader(final InputStream byteStream) {
		if (byteStream == null) {
			throw new NullPointerException("byte stream must not be null!");
		}
		// initialize underlying byte stream
		this.byteStream = byteStream;

		// initialize bit buffer
		currentByte = 0;
		currentBitIndex = -1; // indicates that no byte read yet
		markByte = currentByte;
		markBitIndex = currentBitIndex;
	}

	// Methods /////////////////////////////////////////////////////////////////

	/**
	 * Mark current position to be reseted afterwards.
	 * 
	 * @see #reset()
	 */
	public void mark() {
		markByte = currentByte;
		markBitIndex = currentBitIndex;
		byteStream.mark(0);
	}

	/**
	 * Reset reader to last mark.
	 * 
	 * @see #mark()
	 */
	public void reset() {
		try {
			byteStream.reset();
		} catch (IOException e) {
		}
		currentByte = markByte;
		currentBitIndex = markBitIndex;
	}

	/**
	 * Close reader.
	 * Free resource and clear left bytes.
	 */
	public void close() {
		skipBytes(available());
		currentByte = 0;
		currentBitIndex = -1; // indicates that no byte read yet
	}

	/**
	 * Skip bits.
	 * 
	 * @param numBits number of bits to skip
	 * @return actual number of skipped bits
	 * @since 2.5
	 */
	public long skip(long numBits) {
		int skipped = 0;
		if (currentBitIndex >= 0) {
			skipped = currentBitIndex + 1;
			numBits -= skipped;
			currentBitIndex = -1;
		}
		int left = (int) (numBits & 0x07);
		numBits = skipBytes(numBits / Byte.SIZE) * Byte.SIZE;
		if (left > 0) {
			if (available() > 0) {
				readCurrentByte();
				currentBitIndex -= left;
			} else {
				left = 0;
			}
		}

		return numBits + left + skipped;
	}

	/**
	 * 
	 * Reads a sequence of bits from the stream.
	 * 
	 * @param numBits
	 *            The number of bits to read.
	 * 
	 * @return A Long containing the bits read.
	 * @throws IllegalArgumentException if provided numBits exceeds available bytes
	 */
	public long readLong(final int numBits) {
		if (numBits < 0 || numBits > Long.SIZE) {
			throw new IllegalArgumentException("bits must be in range 0 ... 64!");
		}
		long bits = 0; // initialize all bits to zero

		if (currentBitIndex < 0 && (numBits & 0x7) == 0) {
			// byte-wise, no maverick bits left
			for (int i = 0; i < numBits; i += 8) {
				bits <<= 8;
				bits |= readByte();
			}
		} else {
			for (int i = numBits - 1; i >= 0; i--) {

				// check whether new byte needs to be read
				if (currentBitIndex < 0) {
					readCurrentByte();
				}

				// test current bit
				boolean bit = (currentByte >> currentBitIndex & 1) != 0;
				if (bit) {
					// set bit at i-th position
					bits |= (1L << i);
				}

				// decrease current bit index
				--currentBitIndex;
			}
		}

		return bits;
	}

	/**
	 * Reads a sequence of bits from the stream.
	 * 
	 * @param numBits
	 *            The number of bits to read.
	 * 
	 * @return An integer containing the bits read.
	 * @throws IllegalArgumentException if provided numBits exceeds available bytes
	 */
	public int read(final int numBits) {
		if (numBits < 0 || numBits > Integer.SIZE) {
			throw new IllegalArgumentException("bits must be in range 0 ... 32!");
		}

		int bits = 0; // initialize all bits to zero

		if (currentBitIndex < 0 && (numBits & 0x7) == 0) {
			// byte-wise, no maverick bits left
			for (int i = 0; i < numBits; i += 8) {
				bits <<= 8;
				bits |= readByte();
			}
		} else {
			for (int i = numBits - 1; i >= 0; i--) {

				// check whether new byte needs to be read
				if (currentBitIndex < 0) {
					readCurrentByte();
				}

				// test current bit
				boolean bit = (currentByte >> currentBitIndex & 1) != 0;
				if (bit) {
					// set bit at i-th position
					bits |= (1 << i);
				}

				// decrease current bit index
				--currentBitIndex;
			}
		}

		return bits;
	}

	/**
	 * Reads a sequence of bytes from the stream.
	 * 
	 * @param count
	 *            The number of bytes to read.
	 * 
	 * @return The sequence of bytes read from the stream.
	 * @throws IllegalArgumentException if provided count exceeds available bytes
	 */
	public byte[] readBytes(final int count) {

		int available = available();
		int bytesToRead = count;

		// for negative count values, read all bytes left
		if (bytesToRead < 0) {
			bytesToRead = available;
		} else if (bytesToRead > available) {
			throw new IllegalArgumentException(
					"requested " + count + " bytes exceeds available " + available + " bytes.");
		}

		if (bytesToRead == 0) {
			return Bytes.EMPTY;
		}

		// allocate byte array
		byte[] bytes = new byte[bytesToRead];

		// are there bits left to read in buffer?
		if (currentBitIndex >= 0) {

			for (int i = 0; i < bytesToRead; i++) {
				bytes[i] = (byte) read(Byte.SIZE);
			}

		} else {

			// if bit buffer is empty, call can be delegated
			// to byte stream to increase performance
			readBytes(bytes, 0, bytes.length);
		}

		return bytes;
	}

	/**
	 * Reads the next byte from the stream.
	 * 
	 * @return The next byte.
	 * @throws IllegalArgumentException if no bytes are available
	 */
	public byte readNextByte() {
		if (currentBitIndex >= 0) {
			return (byte) read(Byte.SIZE);
		} else {
			return (byte) readByte();
		}
	}

	/**
	 * Reads the complete sequence of bytes left in the stream.
	 * 
	 * @return The sequence of bytes left in the stream.
	 */
	public byte[] readBytesLeft() {
		return readBytes(-1);
	}

	/**
	 * Read variable length byte arrays.
	 * 
	 * Read first the length of the variable bytes according the size in
	 * {@code numBits}. For the length {@code -1}, {@code null} is returned, and
	 * for the length {@code 0}, {@link Bytes#EMPTY} is returned.
	 * 
	 * @param numBits number of bits used for the size.
	 * @return read byte array, {@code null}, or empty array.
	 * @since 3.0
	 */
	public byte[] readVarBytes(int numBits) {
		int varLengthBits = DatagramWriter.getVarLengthBits(numBits);
		int nullLengthValue = DatagramWriter.getNullLengthValue(varLengthBits);
		int len = read(varLengthBits);
		if (len == nullLengthValue) {
			return null;
		} else {
			return readBytes(len);
		}
	}

	/**
	 * Assert, that all data is read.
	 * 
	 * @param message message to include in {@link IllegalStateException}
	 *            message.
	 * @throws IllegalStateException if bits are left unread
	 * @since 3.0
	 */
	public void assertFinished(String message) {
		int left = bitsLeft();
		if (left > 0) {
			throw new IllegalStateException(message + " not finised! " + left + " bits left.");
		}
	}

	/**
	 * Checks if there are any more bytes available on the stream.
	 * 
	 * @return {@code true}, if there are bytes left to read, {@code false},
	 *         otherwise.
	 */
	public boolean bytesAvailable() {
		return available() > 0;
	}

	/**
	 * Checks whether a given number of bytes can be read.
	 * 
	 * @param expectedBytes the number of bytes.
	 * @return {@code true} if the remaining number of bytes in the buffer is at
	 *         least <em>expectedBytes</em>. {@code false}, otherwise.
	 */
	public boolean bytesAvailable(final int expectedBytes) {
		int bytesLeft = available();
		return bytesLeft >= expectedBytes;
	}

	/**
	 * Gets the number of remaining bits that can be read from the datagram.
	 *  
	 * @return the number of bits
	 */
	public int bitsLeft() {
		return (available() * Byte.SIZE) + (currentBitIndex + 1);
	}

	/**
	 * Decrypt content to read.
	 * 
	 * Requires reader created with {@link #createRangeReader(int)}.
	 * 
	 * @param cipher cipher to use
	 * @param parameterSpec parameter spec for cipher
	 * @param key key
	 * @throws GeneralSecurityException if an crypto-error occurred or reader is
	 *             not created with {@link #createRangeReader(int)}.
	 * @since 3.0
	 */
	public void decrypt(Cipher cipher, AlgorithmParameterSpec parameterSpec, SecretKey key)
			throws GeneralSecurityException {
		if (byteStream instanceof RangeInputStream) {
			((RangeInputStream) byteStream).decrypt(cipher, parameterSpec, key);
		} else {
			throw new GeneralSecurityException("decrpyt requires range-reader!");
		}
	}

	/**
	 * Update message digest with available content.
	 * 
	 * Requires reader created with {@link #createRangeReader(int)}.
	 * 
	 * @param md message digest to use
	 * @throws GeneralSecurityException if reader is not created with
	 *             {@link #createRangeReader(int)}.
	 * @since 3.0
	 */
	public void updateMessageDigest(MessageDigest md) throws GeneralSecurityException {
		if (byteStream instanceof RangeInputStream) {
			((RangeInputStream) byteStream).updateMessageDigest(md);
		} else {
			throw new GeneralSecurityException("update message-digest requires range-reader!");
		}
	}

	/**
	 * Create reader for provided range.
	 * 
	 * @param count size of the range in bytes
	 * @return reader
	 * @throws IllegalStateException if some bits of the current byte are unread
	 * @throws IllegalArgumentException if provided count exceeds available
	 *             bytes
	 */
	public DatagramReader createRangeReader(int count) {
		return new DatagramReader(createRangeInputStream(count));
	}

	/**
	 * Create input stream for provided range.
	 * 
	 * @param count size of the range in bytes
	 * @return input stream
	 * @throws IllegalStateException if some bits of the current byte are unread
	 * @throws IllegalArgumentException if provided count exceeds available
	 *             bytes
	 */
	public ByteArrayInputStream createRangeInputStream(int count) {
		if (currentBitIndex > 0) {
			throw new IllegalStateException(currentBitIndex + " bits unread!");
		}
		int available = available();
		if (available < count) {
			throw new IllegalArgumentException(
					"requested " + count + " bytes exceeds available " + available + " bytes.");
		}
		if (byteStream instanceof RangeInputStream) {
			RangeInputStream range = (RangeInputStream) byteStream;
			return range.range(count);
		} else {
			byte[] range = new byte[count];
			readBytes(range, 0, count);
			return new RangeInputStream(range);
		}
	}

	// Utilities ///////////////////////////////////////////////////////////////

	/**
	 * Get available bytes from {@link #byteStream}.
	 * 
	 * @return available bytes, or {@code -1}, if an error occurred.
	 * @since 3.0
	 */
	private int available() {
		try {
			return byteStream.available();
		} catch (IOException e) {
			return -1;
		}
	}

	/**
	 * Skip bytes in {@link #byteStream}.
	 * 
	 * @param skip number of bytes to skip
	 * @return actually skipped bytes, or {@code -1}, if an error occurred.
	 * @since 3.0
	 */
	private long skipBytes(long skip) {
		try {
			return byteStream.skip(skip);
		} catch (IOException e) {
			return -1;
		}
	}

	/**
	 * Reads new bits from the stream.
	 * 
	 * @throws IllegalArgumentException if no bytes are available
	 */
	private void readCurrentByte() {
		// try to read from byte stream
		currentByte = (byte) readByte();
		// reset current bit index
		currentBitIndex = Byte.SIZE - 1;
	}

	/**
	 * Reads new bits from the stream.
	 * 
	 * @throws IllegalArgumentException if no bytes are available
	 */
	private int readByte() {
		try {
			// try to read from byte stream
			int val = byteStream.read();

			if (val < 0) {
				// end of stream reached
				throw new IllegalArgumentException("requested byte exceeds available bytes!");
			}
			return val;
		} catch (IOException e) {
			throw new IllegalArgumentException("request byte fails!", e);
		}
	}

	/**
	 * Read bytes from stream.
	 * 
	 * @param buffer buffer to read bytes in
	 * @param offset offset in buffer to start to read bytes in
	 * @param length number of bytes to read
	 * @return number of actual read bytes.
	 * @throws IllegalArgumentException if no bytes are available
	 * @since 3.0
	 */
	private int readBytes(byte[] buffer, int offset, int length) {
		try {
			return byteStream.read(buffer, offset, length);
		} catch (IOException e) {
			throw new IllegalArgumentException("request bytes fails!", e);
		}
	}
}
