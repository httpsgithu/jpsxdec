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
 * PSXMediaFF9.java
 */

package jpsxdec.media;

import java.io.DataInputStream;
import java.io.IOException;
import java.util.NoSuchElementException;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import jpsxdec.audiodecoding.ADPCMDecodingContext;
import jpsxdec.audiodecoding.StrADPCMDecoder;
import jpsxdec.cdreaders.CDSectorReader;
import jpsxdec.cdreaders.CDXASector;
import jpsxdec.sectortypes.PSXSectorRangeIterator;
import jpsxdec.sectortypes.PSXSector;
import jpsxdec.sectortypes.PSXSector.PSXSectorFF9Abstract;
import jpsxdec.sectortypes.PSXSector.PSXSectorFF9Audio;
import jpsxdec.sectortypes.PSXSector.PSXSectorFF9Video;
import jpsxdec.util.NotThisTypeException;
import jpsxdec.media.StrFpsCalc.*;

// TODO:
import jpsxdec.util.IO.Short2DArrayInputStream;

public class PSXMediaFF9 extends PSXMedia.PSXMediaStreaming.PSXMediaVideo {
    
    long m_lngStartFrame = -1;
    long m_lngEndFrame = -1;
    
    public PSXMediaFF9(PSXSectorRangeIterator oSectIterator) 
                throws NotThisTypeException, IOException 
    {
        super(oSectIterator);
        
        PSXSector oPsxSect = oSectIterator.peekNext();
        
        if (!(oPsxSect instanceof PSXSectorFF9Abstract))
            throw new NotThisTypeException();
        
        if (DebugVerbose > 2)
            System.err.println(oPsxSect.toString());
        
        PSXSectorFF9Abstract oFF9Sect;
        
        oFF9Sect = (PSXSectorFF9Abstract)oPsxSect;

        super.m_iStartSector = oPsxSect.getSector();
        super.m_iEndSector = m_iStartSector;

        long iCurFrame = oFF9Sect.getFrameNumber();
        m_lngStartFrame = oFF9Sect.getFrameNumber();
        m_lngEndFrame = oFF9Sect.getFrameNumber();
        
        oSectIterator.skipNext();
        while (oSectIterator.hasNext()) {
            oPsxSect = oSectIterator.peekNext();
            
            if (oPsxSect instanceof PSXSectorFF9Abstract) {
                
                oFF9Sect = (PSXSectorFF9Abstract)oPsxSect;
                if (oFF9Sect.getFrameNumber() == iCurFrame ||
                    oFF9Sect.getFrameNumber() == iCurFrame+1) 
                {
                    iCurFrame = oFF9Sect.getFrameNumber();
                    m_lngEndFrame = iCurFrame;
                } else {
                    break;
                }
                
                m_iEndSector = oPsxSect.getSector();
            }  else {
                break; // some other sector type? we're done.
            }
            
            if (oPsxSect != null && DebugVerbose > 2)
                System.err.println(oPsxSect.toString());
            
            oSectIterator.skipNext();
        } // while
        
    }

    public PSXMediaFF9(CDSectorReader oCD, String sSerial) throws NotThisTypeException
    {
        super(oCD, sSerial, "FF9");
        try {
            IndexLineParser parse = new IndexLineParser(
                    "$| Frames #-#", sSerial);

            parse.skip();
            m_lngStartFrame = parse.get(m_lngStartFrame);
            m_lngEndFrame   = parse.get(m_lngEndFrame);
        
        } catch (NumberFormatException ex) {
            throw new NotThisTypeException();
        } catch (IllegalArgumentException ex) {
            throw new NotThisTypeException();
        } catch (NoSuchElementException ex) {
            throw new NotThisTypeException();
        }
        
    }
    public String toString() {
        return super.toString("FF8") + String.format(
                "| Frames %d-%d",
                m_lngStartFrame, m_lngEndFrame);
    }

    public int getMediaType() {
        return PSXMedia.MEDIA_TYPE_VIDEO_AUDIO;
    }
    
    @Override
    public boolean hasVideo() {
        return true;
    }

    @Override
    public int getAudioChannels() {
        return 2;
    }
    
    @Override
    public long getStartFrame() {
        return m_lngStartFrame;
    }

    @Override
    public long getEndFrame() {
        return m_lngEndFrame;
    }

    @Override
    public FramesPerSecond[] getPossibleFPS() {
        return new FramesPerSecond[] {new FramesPerSecond(15, 1, 150)};
    }
   
    
    //--------------------------------------------------------------------------
    //-- Playing ---------------------------------------------------------------
    //--------------------------------------------------------------------------
    
    PSXSectorFF9Video[] m_oFrameChunks;
    PSXSectorFF9Audio m_oLeftAudioChunk;
    private ADPCMDecodingContext m_oLeftContext;
    private ADPCMDecodingContext m_oRightContext;
    
    @Override
    protected void startPlay() {
        m_oFrameChunks = new PSXSectorFF9Video[8];
        m_oLeftContext = new ADPCMDecodingContext(1.0);
        m_oRightContext = new ADPCMDecodingContext(1.0);
    }
    
    @Override
    protected boolean playSector(PSXSector oPSXSect) throws IOException {
        //throw new UnsupportedOperationException("FF9 audio decoding doesn't quite work yet. :(");
        
        if (oPSXSect instanceof PSXSectorFF9Video) {
            // only process the frame if there is a listener for it
            if (super.m_oVidDemux != null || super.m_oMdec != null || super.m_oFrame != null) {
                
            }
        } else if (oPSXSect instanceof PSXSectorFF9Audio) {
            // only process the audio if there is a listener for it
            if (super.m_oAudio != null) {
                PSXSectorFF9Audio oAudChk = (PSXSectorFF9Audio)oPSXSect;
                
                if (m_oLeftAudioChunk == null) {
                    m_oLeftAudioChunk = oAudChk;
                } else {
                    short[][] asi = new short[2][];
                    // alas we don't know yet
                    asi[0] = StrADPCMDecoder.DecodeMoreFF8(new DataInputStream(m_oLeftAudioChunk),
                                                            m_oLeftContext, 3220-28);
                    asi[1] = StrADPCMDecoder.DecodeMoreFF8(new DataInputStream(oAudChk),
                                                            m_oRightContext, 3220-28);

                    boolean bln = m_oAudio.event(
                        new AudioInputStream(
                            new Short2DArrayInputStream(asi), 
                            new AudioFormat(
                                47880,
                                16,        
                                2, 
                                true,      
                                false
                            ),
                            AudioSystem.NOT_SPECIFIED
                        ),
                        oAudChk.getSector()
                    );
                    m_oLeftAudioChunk = null;
                    if (bln) return true;
                }
            }
        }
        return false;
        
    }
    
    @Override
    protected void endPlay() throws IOException {
        m_oFrameChunks = null;
        m_oLeftAudioChunk = null;
        m_oLeftContext = null;
        m_oRightContext = null;
    }

    @Override
    public void seek(int iFrame) throws IOException {
        // clamp the desired frame
        if (iFrame < m_lngStartFrame) 
            iFrame = (int)m_lngStartFrame; 
        else if (iFrame > m_lngEndFrame) 
            iFrame = (int)m_lngEndFrame;
        // calculate an estimate where the frame will land
        double percent = (iFrame - m_lngStartFrame) / (double)(m_lngEndFrame - m_lngStartFrame);
        // backup 10% of the size of the media to 
        // hopefully land shortly before the frame
        int iSect = (int)
                ( (super.m_iEndSector - super.m_iStartSector) * (percent-0.1) ) 
                + super.m_iStartSector;
        if (iSect < super.m_iStartSector) iSect = super.m_iStartSector;
        
        super.m_oCDIter.gotoIndex(iSect);
        
        // now seek ahead until we read the desired frame
        CDXASector oCDSect = super.m_oCDIter.peekNext();
        PSXSector oPsxSect = PSXSector.SectorIdentifyFactory(oCDSect);
        while (!(oPsxSect instanceof PSXSectorFF9Abstract) ||
               ((PSXSectorFF9Abstract)oPsxSect).getFrameNumber() < iFrame) 
        {
            super.m_oCDIter.skipNext();
            oCDSect = super.m_oCDIter.peekNext();
            oPsxSect = PSXSector.SectorIdentifyFactory(oCDSect);
        }
        
        // in case we ended up past the desired frame, backup until we're
        // at the first sector of the desired frame
        while (!(oPsxSect instanceof PSXSectorFF9Abstract) ||
               ((PSXSectorFF9Abstract)oPsxSect).getFrameNumber() > iFrame ||
               ((PSXSectorFF9Abstract)oPsxSect).getFF9ChunkNumber() > 0)
        {
            super.m_oCDIter.gotoIndex(m_oCDIter.getIndex() - 1);
            oCDSect = super.m_oCDIter.peekNext();
            oPsxSect = PSXSector.SectorIdentifyFactory(oCDSect);
        }
        
    }

}


