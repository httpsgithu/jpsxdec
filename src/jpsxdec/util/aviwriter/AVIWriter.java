/*
 * jPSXdec: Playstation 1 Media Decoder/Converter in Java
 * Copyright (C) 2007  Michael Sabin
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor,   
 * Boston, MA  02110-1301, USA.
 *
 */

/*
 * AVIWriter.java
 */

package jpsxdec.util.aviwriter;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.awt.image.ColorModel;
import javax.imageio.ImageIO;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.io.File;
import java.io.ByteArrayOutputStream;
import java.util.LinkedList;
//import java.util.Arrays; // for Java 1.6 Arrays.copyRange()
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import jpsxdec.util.aviwriter.AVIOLDINDEX._avioldindex_entry;

/**<pre> 
 * AVI encoder to write uncompressed, RGB DIB video, or compressed MJPG video, 
 * along with uncompressed PCM audio. The resulting MJPG AVI seems playable on 
 * vanilla Windows XP systems, and of course VLC.
 *  
 * This code is originally based on (but now hardly resembles) the ImageJ 
 * package at http://rsb.info.nih.gov/ij
 *
 *      ImageJ is being developed at the National Institutes of Health by an 
 *      employee of the Federal Government in the course of his official duties. 
 *      Pursuant to Title 17, Section 105 of the United States Code, this software 
 *      is not subject to copyright protection and is in the public domain. 
 *      ImageJ is an experimental system. NIH assumes no responsibility whatsoever 
 *      for its use by other parties, and makes no guarantees, expressed or implied, 
 *      about its quality, reliability, or any other characteristic. 
 * 
 * The ImageJ AVIWriter class was based on the FileAvi class written by
 * William Gandler. That FileAvi class is part of Matthew J. McAuliffe's 
 * MIPAV program, available from http://mipav.cit.nih.gov/. This code also
 * appears to be in the public domain.
 * 
 * I owe my MJPG understanding to the jpegtoavi program.
 * http://sourceforge.net/projects/jpegtoavi/
 * Random list of codecs
 * http://www.oltenia.ro/download/pub/windows/media/video/tools/GSpot/gspot22/GSpot22.dat
 * </pre>
 */
public class AVIWriter {

    // -------------------------------------------------------------------------
    // -- Fields ---------------------------------------------------------------
    // -------------------------------------------------------------------------
    
    /** Width of the frame in pixels. */
    private int m_iWidth = -1;
    /** Height of the frame in pixels. */
    private int m_iHeight = -1;
    /** Numerator of the frames/second fraction. */
    private int m_iFrames = -1;
    /** Denominator of the frames/second fraction. */
    private int m_iPerSecond = -1;
    /** Size of the frame data in bytes. Only applicable to DIB AVI.
     *  Each DIB frame submitted is compared to this value to ensure
     *  proper data. */
    private int m_iFrameByteSize = -1;
    /** True if this AVI uses the MJPG codec. If false, uses DIB. */
    private final boolean m_blnMJPG;
    /** Count of the number of frames submitted. */
    private int m_iFrameCount = 0;
    
    /** Number of audio channels. 0 for no audio, 1 for mono, 2 for stereo. */
    private final int m_iChannels;
    /** Number of bytes per sample. */
    private final int m_iBytesPerSample = 2;
    /** Sample rate of the audio. */
    private int m_iSamplesPerSecond = -1;
    /** Number of audio samples submitted. */
    private double m_dblSampleCount = 0;
    
    // -------------------------------------------------------------------------
    // -- Properties -----------------------------------------------------------
    // -------------------------------------------------------------------------
    
    public void setSamplesPerSecond(int i) {
        if (i < 1 ) throw new IllegalArgumentException("Samples/Second must be greater than 0");
        m_iSamplesPerSecond = i;
    }
    public int getSamplesPerSecond() {
        return m_iSamplesPerSecond;
    }
    public void setFramesPerSecond(int i, int j) {
        if (i < 1 || j < 1) throw new IllegalArgumentException("frames/sec must be greater than 0");
        m_iFrames = i;
        m_iPerSecond = j;
    }
    public int getFramesPerSecNum() {
        return m_iFrames;
    }
    public int getFramesperSecDenom() {
        return m_iPerSecond;
    }
    public void setDimensions(int i, int j) {
        if (i < 1 || j < 1) throw new IllegalArgumentException("Dimensions must be greater than 0");
        if (m_iWidth < 0)
            m_iWidth = i;
        else
            throw new IllegalArgumentException("Width has already been set.");
        if (m_iHeight < 0)
            m_iHeight = j;
        else
            throw new IllegalArgumentException("Height has already been set.");
    }
    public int getWidth() {
        return m_iWidth;
    }
    public int getHeight() {
        return m_iHeight;
    }
    
    // -------------------------------------------------------------------------
    // -- AVI Structure --------------------------------------------------------
    // -------------------------------------------------------------------------
    
    private RandomAccessFile raFile;
    
    private Chunk RIFF_chunk;
    private     Chunk LIST_hdr1;
    private         AVIMAINHEADER avih;
    private         Chunk LIST_strl_vid;
    private             Chunk strf_vid;
    private                 AVISTREAMHEADER strh_vid;
    private                 BITMAPINFOHEADER bif;
                        //strf_vid
                    //LIST_strl_vid
    private         Chunk LIST_strl_aud;
    private             Chunk strf_aud;
    private                 AVISTREAMHEADER strh_aud;
    private                 WAVEFORMATEX wavfmt;
                        //strf_aud
                    //LIST_strl_aud
                //LIST_hdr1
    private     Chunk LIST_movi;
                  /* image and audio chunk data */
                //LIST_movi
    private     AVIOLDINDEX avioldidx;
            //RIFF_chunk
    
    private LinkedList<_avioldindex_entry> indexList;
    
    /** Opens and prepares an AVI file for writing video/audio data. */
    public AVIWriter(final File oOutputfile, 
                     final boolean blnMJPG,
                     final int iAudChannels) 
            throws IOException
    {
        if (iAudChannels < 0 || iAudChannels > 2)
            throw new IllegalArgumentException("Channels must be 0, 1 or 2");
        if (blnMJPG && !CAN_ENCODE_JPG)
            throw new UnsupportedOperationException("Unable to encode 'jpeg' on this platform.");
        
        m_blnMJPG = blnMJPG;
        m_iChannels = iAudChannels;
        
        raFile = new RandomAccessFile(oOutputfile, "rw");
        raFile.setLength(0); // trim the file to 0

        //----------------------------------------------------------------------
        // Setup the header structure. 
        // Actual values will be filled in when avi is closed.
        
        RIFF_chunk = new Chunk(raFile, "RIFF", "AVI ");
        
            LIST_hdr1 = new Chunk(raFile, "LIST", "hdrl"); 
        
                avih = new AVIMAINHEADER();
                avih.makePlaceholder(raFile);
            
                LIST_strl_vid = new Chunk(raFile, "LIST", "strl");

                    strh_vid = new AVISTREAMHEADER();
                    strh_vid.makePlaceholder(raFile);

                    strf_vid = new Chunk(raFile, "strf");

                        bif = new BITMAPINFOHEADER();
                        bif.makePlaceholder(raFile);                               

                    strf_vid.endChunk(raFile);
                    
                LIST_strl_vid.endChunk(raFile);

                if (m_iChannels > 0) { // if there is audio
                LIST_strl_aud = new Chunk(raFile, "LIST", "strl");

                    strh_aud = new AVISTREAMHEADER();
                    strh_aud.makePlaceholder(raFile);

                    strf_aud = new Chunk(raFile, "strf");

                        wavfmt = new WAVEFORMATEX();
                        wavfmt.makePlaceholder(raFile);

                    strf_aud.endChunk(raFile);

                LIST_strl_aud.endChunk(raFile);
                }

            LIST_hdr1.endChunk(raFile);
            
            LIST_movi = new Chunk(raFile, "LIST", "movi");

            // now we're ready to start accepting video/audio data
            
            // generate an index as we write 'movi' section
            indexList = new LinkedList<_avioldindex_entry>();
    }
    
    /** Assumes width/height is correct. 
     * abData should either be DIB data 
     * (rows inverted and widths padded to 4 byte boundaries),
     * or JPEG data with the 'JFIF' header text changed to 'AVI1'. */
    public void writeFrame(byte[] abData) throws IOException {
        if (raFile == null) throw new IOException("Avi file is closed");
        // only keep track of frame data size if it's DIB. They should all be the same
        if (!m_blnMJPG) {
            if (m_iFrameByteSize < 0)
                m_iFrameByteSize = abData.length;
            else if (m_iFrameByteSize != abData.length)
                throw new IllegalArgumentException("Frame data size is not consistent");
        }
        
        writeStreamDataChunk(abData, true);
        
    }
    
    /** Converts a BufferedImage to proper avi format and writes it. */
    public void writeFrame(BufferedImage bi) throws IOException {
        if (raFile == null) throw new IOException("Avi file is closed");
        if (m_iWidth < 0)
            m_iWidth = bi.getWidth();
        else if (m_iWidth != bi.getWidth())
            throw new IllegalArgumentException("Frame width is inconsistent.");
        
        if (m_iHeight < 0)
            m_iHeight = bi.getHeight();
        else if (m_iHeight != bi.getHeight())
            throw new IllegalArgumentException("Frame height is inconsistent.");
        
        if (m_blnMJPG) {
            writeStreamDataChunk(Image2MJPEG(bi), true);
        } else {
            byte[] b;
            b = Image2DIB(bi, m_iFrameByteSize);
            m_iFrameByteSize = b.length;
            writeStreamDataChunk(b, true);
        }
    }
    
    public void writeAudio(byte[] abData) throws IOException {
        if (raFile == null) throw new IOException("Avi file is closed");
        writeStreamDataChunk(abData, false);
    }

    public void writeAudio(AudioInputStream oData) throws IOException {
        if (raFile == null) throw new IOException("Avi file is closed");
        
        AudioFormat fmt = oData.getFormat();
        
        if (m_iBytesPerSample < 0)
        {}//m_iBytesPerSample = fmt.getFrameSize();
        else if (fmt.getSampleSizeInBits() != m_iBytesPerSample * 8)
            throw new IOException();
        
        if (m_iChannels != fmt.getChannels())
            throw new IOException();
        
        if (m_iSamplesPerSecond < 0)
            m_iSamplesPerSecond = (int)fmt.getSampleRate();
        else if (m_iSamplesPerSecond != (int)fmt.getSampleRate())
            throw new IOException();
        
        Chunk data_size;

        _avioldindex_entry idxentry = new _avioldindex_entry();
        idxentry.dwOffset = (int)(raFile.getFilePointer() - (LIST_movi.getStart() + 4));
        
        idxentry.dwChunkId = AVIstruct.string2int("01wb");
        idxentry.dwFlags = 0;
        
        data_size = new Chunk(raFile, "01wb");

            // write the data, padded to 4 byte boundary
            byte[] b = new byte[1024];
            int i;
            while ((i = oData.read(b)) > 0) {
                m_dblSampleCount += (double)i / m_iBytesPerSample / m_iChannels;
                raFile.write(b, 0, i);
            }
            
            int remaint = (int)(4 - (  (raFile.getFilePointer() - (data_size.getStart()+4) )  % 4)) % 4;
            while (remaint > 0) { raFile.write(0); remaint--; }
            
        // end the chunk
        data_size.endChunk(raFile);
        
        // add the index to the list
        idxentry.dwSize = (int)data_size.getSize();
        indexList.add(idxentry);
    }
    
    private void writeStreamDataChunk(byte[] abData, boolean blnIsVideo) throws IOException {

        Chunk data_size;

        _avioldindex_entry idxentry = new _avioldindex_entry();
        idxentry.dwOffset = (int)(raFile.getFilePointer() - (LIST_movi.getStart() + 4));

        if (blnIsVideo) { // if video

            idxentry.dwChunkId = AVIstruct.string2int("00db");
            if (m_iFrameCount == 0)
                idxentry.dwFlags = AVIOLDINDEX.AVIIF_KEYFRAME; // Write the flags - select AVIIF_KEYFRAME
            else                                                // AVIIF_KEYFRAME 0x00000010L
                idxentry.dwFlags = 0x00;    // The flag indicates key frames in the video sequence.

            m_iFrameCount++;
            data_size = new Chunk(raFile, "00db");
        } else { // if audio
            idxentry.dwChunkId = AVIstruct.string2int("01wb");
            idxentry.dwFlags = 0;
            // TODO: Probably should throw an error if half a sample is provided
            m_dblSampleCount += (double)abData.length / m_iBytesPerSample / m_iChannels;
            data_size = new Chunk(raFile, "01wb");
        }

        // write the data, padded to 4 byte boundary
        int remaint = (4 - (abData.length % 4)) % 4;
        raFile.write(abData);
        while (remaint > 0) { raFile.write(0); remaint--; }
        // end the chunk
        data_size.endChunk(raFile);
        
        // add the index to the list
        idxentry.dwSize = (int)data_size.getSize();
        indexList.add(idxentry);
    }
    
    
    public void close() throws IOException {
        if (raFile == null) throw new IOException("Avi file is closed");
        ////////////////////////////////////////////////////////////////////////
        if (m_iFrames < 1 || m_iPerSecond < 1)
            throw new IllegalStateException("Must set frames/second before closing avi");
        if (m_iChannels > 0 && m_iSamplesPerSecond < 1)
            throw new IllegalStateException("Must set samples/second before closing avi");
        ////////////////////////////////////////////////////////////////////////
        
            LIST_movi.endChunk(raFile);
            
            // write idx
            avioldidx = new AVIOLDINDEX(indexList.toArray(new _avioldindex_entry[0]));
            avioldidx.write(raFile);
            // /write idx
            
        RIFF_chunk.endChunk(raFile);
        
        //######################################################################
        //## Fill the headers fields ###########################################
        //######################################################################
        
        //avih.fcc                 = 'avih';  // the avih sub-CHUNK
        //avih.cb                  = 0x38;    // the length of the avih sub-CHUNK (38H) not including the
                                              // the first 8 bytes for avihSignature and the length            
        avih.dwMicroSecPerFrame = (int)((m_iFrames/(double)m_iPerSecond)*1.0e6);
        avih.dwMaxBytesPerSec      = 0;       // (maximum data rate of the file in bytes per second)
        avih.dwPaddingGranularity  = 0;
        avih.dwFlags               = 0x10;    // just set the bit for AVIF_HASINDEX
                                              // 10H AVIF_HASINDEX: The AVI file has an idx1 chunk containing
                                              // an index at the end of the file.  For good performance, all
                                              // AVI files should contain an index.                         
        avih.dwTotalFrames         = m_iFrameCount;  // total frame number
        avih.dwInitialFrames       = 0;       // Initial frame for interleaved files.
                                              // Noninterleaved files should specify 0.
        if (m_iChannels > 0)
            avih.dwStreams         = 2;       // number of streams in the file - here 1 video and zero audio.
        else
            avih.dwStreams         = 1;       // number of streams in the file - here 1 video and zero audio.
        avih.dwSuggestedBufferSize = 0;       // Suggested buffer size for reading the file.
                                              // Generally, this size should be large enough to contain the largest
                                              // chunk in the file.
                                              // dwSuggestedBufferSize - Suggested buffer size for reading the file.
        avih.dwWidth               = m_iWidth;  // image width in pixels
        avih.dwHeight              = m_iHeight; // image height in pixels
        //avih.dwReserved1         = 0;       //  Microsoft says to set the following 4 values to 0.
        //avih.dwReserved2         = 0;       //  
        //avih.dwReserved3         = 0;       //  
        //avih.dwReserved4         = 0;       //  
        
        
        //######################################################################
        // AVISTREAMHEADER for video
        
        //strh_vid.fcc                  = 'strh';              // strh sub-CHUNK
        //strh_vid.cb                   = 56;                  // the length of the strh sub-CHUNK
        strh_vid.fccType                = AVIstruct.string2int("vids"); // the type of data stream - here vids for video stream
       // Write DIB for Microsoft Device Independent Bitmap.  Note: Unfortunately,
       // at least 3 other four character codes are sometimes used for uncompressed
       // AVI videos: 'RGB ', 'RAW ', 0x00000000
        if (m_blnMJPG)
            strh_vid.fccHandler         = AVIstruct.string2int("MJPG");
        else
            strh_vid.fccHandler         = AVIstruct.string2int("DIB ");
        strh_vid.dwFlags                = 0;
        strh_vid.wPriority              = 0;
        strh_vid.wLanguage              = 0;
        strh_vid.dwInitialFrames        = 0;
        strh_vid.dwScale                = m_iPerSecond;
        strh_vid.dwRate                 = m_iFrames; // frame rate for video streams
        strh_vid.dwStart                = 0;         // this field is usually set to zero
        strh_vid.dwLength               = m_iFrameCount; // playing time of AVI file as defined by scale and rate
                                               // Set equal to the number of frames
        strh_vid.dwSuggestedBufferSize  = 0;   // Suggested buffer size for reading the stream.
                                               // Typically, this contains a value corresponding to the largest chunk
                                               // in a stream.
        strh_vid.dwQuality              = -1;  // encoding quality given by an integer between
                                               // 0 and 10,000.  If set to -1, drivers use the default 
                                               // quality value.
        strh_vid.dwSampleSize           = 0;
        strh_vid.left                   = 0;
        strh_vid.top                    = 0;
        strh_vid.right                  = 0;
        strh_vid.bottom                 = 0;   

        //######################################################################
        // BITMAPINFOHEADER
        
        //bif.biSize        = 40;      // Write header size of BITMAPINFO header structure
                                       // Applications should use this size to determine which BITMAPINFO header structure is 
                                       // being used.  This size includes this biSize field.                                 
        bif.biWidth         = m_iWidth;  // BITMAP width in pixels
        bif.biHeight        = m_iHeight; // image height in pixels.  If height is positive,
                                       // the bitmap is a bottom up DIB and its origin is in the lower left corner.  If 
                                       // height is negative, the bitmap is a top-down DIB and its origin is the upper
                                       // left corner.  This negative sign feature is supported by the Windows Media Player, but it is not
                                       // supported by PowerPoint.                                                                        
        //bif.biPlanes      = 1;       // biPlanes - number of color planes in which the data is stored
                                       // This must be set to 1.
        bif.biBitCount      = 24;      // biBitCount - number of bits per pixel #
        if (m_blnMJPG)                 // 0L for BI_RGB, uncompressed data as bitmap
            bif.biCompression   = AVIstruct.string2int("MJPG"); 
        else // type of compression used
            bif.biCompression   = BITMAPINFOHEADER.BI_RGB; 
        bif.biSizeImage     = 0;
        bif.biXPelsPerMeter = 0;       // horizontal resolution in pixels
        bif.biYPelsPerMeter = 0;       // vertical resolution in pixels
                                       // per meter
        bif.biClrUsed       = 0;       //
        bif.biClrImportant  = 0;       // biClrImportant - specifies that the first x colors of the color table 
                                       // are important to the DIB.  If the rest of the colors are not available,
                                       // the image still retains its meaning in an acceptable manner.  When this
                                       // field is set to zero, all the colors are important, or, rather, their
                                       // relative importance has not been computed.
        
        //######################################################################
        // AVISTREAMHEADER for audio

        if (m_iChannels > 0) {
            //strh.fcc                  = 'strh';              // strh sub-CHUNK
            //strh.cb                   = 56;                  // length of the strh sub-CHUNK
            strh_aud.fccType                = AVIstruct.string2int("auds"); // Write the type of data stream - here auds for audio stream
            strh_aud.fccHandler             = 0; // no fccHandler for wav
            strh_aud.dwFlags                = 0;
            strh_aud.wPriority              = 0;
            strh_aud.wLanguage              = 0;
            strh_aud.dwInitialFrames        = 0;
            strh_aud.dwScale                = 1;
            strh_aud.dwRate                 = m_iSamplesPerSecond; // sample rate for audio streams
            strh_aud.dwStart                = 0;   // this field is usually set to zero
            strh_aud.dwLength               = (int)m_dblSampleCount;   // playing time of AVI file as defined by scale and rate
                                                   // Set equal to the number of audio samples in file?
            strh_aud.dwSuggestedBufferSize  = 0;   // Suggested buffer size for reading the stream.
                                                   // Typically, this contains a value corresponding to the largest chunk
                                                   // in a stream.
            strh_aud.dwQuality              = -1;  // encoding quality given by an integer between
                                                   // 0 and 10,000.  If set to -1, drivers use the default 
                                                   // quality value.
            strh_aud.dwSampleSize           = 0;
            strh_aud.left                   = 0;
            strh_aud.top                    = 0;
            strh_aud.right                  = 0;
            strh_aud.bottom                 = 0;   

            //######################################################################
            // WAVEFORMATEX

            wavfmt.wFormatTag       = WAVEFORMATEX.WAVE_FORMAT_PCM;
            wavfmt.nChannels        = (short)m_iChannels;
            wavfmt.nSamplesPerSec   = m_iSamplesPerSecond;
            wavfmt.nAvgBytesPerSec  = m_iBytesPerSample * m_iSamplesPerSecond * m_iChannels;
            wavfmt.nBlockAlign      = (short)(m_iBytesPerSample * m_iChannels);
            wavfmt.wBitsPerSample   = m_iBytesPerSample * 8;
            //wavfmt.cbSize           = 0; // ignored                

        }
        
        //######################################################################
        //######################################################################
        //######################################################################
        
        // go back and write the headers
        avih.goBackAndWrite(raFile);
        strh_vid.goBackAndWrite(raFile);
        bif.goBackAndWrite(raFile);
        
        if (m_iChannels > 0) {
            strh_aud.goBackAndWrite(raFile);
            wavfmt.goBackAndWrite(raFile);
        }
        
        // and we're done
        raFile.close();
        raFile = null;
        
        RIFF_chunk = null;
            LIST_hdr1 = null;
                avih = null;
                LIST_strl_vid = null;
                    strf_vid = null;
                        strh_vid = null;
                        bif = null;
                LIST_strl_aud = null;
                    strf_aud = null;
                        strh_aud = null;
                        wavfmt = null;
            LIST_movi = null;
            avioldidx = null;
    }
    
    //##########################################################################
    
    private final static void writeString(RandomAccessFile raFile, String s) throws IOException {
        byte[] bytes = s.getBytes("UTF8");
        raFile.write(bytes);
    }

    private final static void write32LE(RandomAccessFile raFile, int v) throws IOException {
        raFile.write(v & 0xFF);
        raFile.write((v >>>  8) & 0xFF);
        raFile.write((v >>> 16) & 0xFF);
        raFile.write((v >>> 24) & 0xFF);
    }
    
    ////////////////////////////////////////////////////////////////////////////
        
    public static byte[] Image2DIB(BufferedImage bmp, int iSize) throws IOException {
        // first make sure this is a 24 bit RGB image
        ColorModel cm = bmp.getColorModel();
        if (bmp.getType() != BufferedImage.TYPE_3BYTE_BGR) {
            // if not, convert it
            BufferedImage buffer = new BufferedImage( bmp.getWidth(), bmp.getHeight(), BufferedImage.TYPE_3BYTE_BGR);
            Graphics2D g = buffer.createGraphics();
            g.drawImage(bmp, 0, 0, null);
            g.dispose();
            bmp = buffer;
        }
        // create a new ByteArrayOutputStream with the same 
        // initial size as the last frame (saves time and memory re-allocation)
        ByteArrayOutputStream oOut;
        if (iSize <= 32) 
            oOut = new ByteArrayOutputStream();
        else
            oOut = new ByteArrayOutputStream(iSize);
        // write the BMP file into the buffer
        ImageIO.write(bmp, "bmp", oOut);
        //ImageIO.write(bmp, "bmp", new File("test.bmp"));
        // and get the array of it
        byte[] abDIB = oOut.toByteArray();
        // get the 'bfOffBits' value, which says where the 
        // image data actually starts (should be 54)
        int iDataStart = read32LE(abDIB, 10);
        // return the data from that byte onward
        return jpsxdec.util.Misc.copyOfRange(abDIB, iDataStart, abDIB.length);
    }
    
    /** Read a 32 little-endian value from a position in an array. */
    private static int read32LE(byte[] ab, int iPos) {
        return (ab[iPos+0]) | 
               (ab[iPos+1] << 8 ) | 
               (ab[iPos+2] << 16) | 
               (ab[iPos+3] << 24);
    } 
    
    /** Converts a BufferedImage into a frame to be written into a MJPG avi. */
    public static byte[] Image2MJPEG(BufferedImage img) throws IOException {
        ByteArrayOutputStream oOut = new ByteArrayOutputStream();
        ImageIO.write(img, "jpeg", oOut);
        
        byte[] abJpg = oOut.toByteArray();
        JPEG2MJPEG(abJpg);
        return abJpg;
    }
    
    /** Converts JPEG file data to be used in an MJPG AVI. The 4 byte
     *  'JFIF' magic number at offset 6 just needs to be changed to
     *  'AVI1'. */
    public static void JPEG2MJPEG(byte [] ab) throws IOException {
        if (ab[6] != 'J' || ab[7] != 'F' || ab[8] != 'I' || ab[9] != 'F')
            throw new IOException("JFIF header not found in jpeg data");
        ab[6] = 'A';
        ab[7] = 'V';
        ab[8] = 'I';
        ab[9] = '1';
    }
    

    /** Represents an AVI 'chunk'. When created, it saves the current
     *  position in the AVI RandomAccessFile. When endChunk() is called,
     *  it temporarily jumps back to the start of the chunk and records how 
     *  many bytes have been written. */
    private static class Chunk {
        final private long m_lngPos;
        private long m_lngSize = -1;
        
        Chunk(RandomAccessFile oRAF, String sChunkName) throws IOException {
            writeString(oRAF, sChunkName);
            m_lngPos = oRAF.getFilePointer();
            oRAF.writeInt(0);
        }
        
         Chunk(RandomAccessFile oRAF, String sChunkName, String sSubChunkName) throws IOException {
            this(oRAF, sChunkName);
            writeString(oRAF, sSubChunkName);
        }
        
        /** Jumps back to saved position in the RandomAccessFile and writes
         *  how many bytes have passed since the position was saved, then
         *  returns to the current position again. */
        public void endChunk(RandomAccessFile oRAF) throws IOException {
            long lngCurPos = oRAF.getFilePointer(); // save this pos
            oRAF.seek(m_lngPos); // go back
            m_lngSize = (lngCurPos - (m_lngPos + 4)); // save number of bytes since start of chunk
            // write it
            write32LE(oRAF, (int)m_lngSize);
            oRAF.seek(lngCurPos); // return to current position
            
            oRAF = null;
        }

        /** After endChunk() has been called, returns the size that was
         *  written. */
        private long getSize() {
            return m_lngSize;
        }
        
        /** Returns the position where the size will be written when
         *  endChunk() is called. */
        private long getStart() {
            return m_lngPos;
        }
    }
    
    private final static boolean CAN_ENCODE_JPG;
    static {
        boolean bln = false;
        for (String s : ImageIO.getReaderFormatNames()) {
            if (s.equals("jpeg")) {
                bln = true;
                break;
            }
        }
        CAN_ENCODE_JPG = bln;
    }

}
