package edu.illinois.library.cantaloupe.processor.codec.openslide;

import java.awt.image.BufferedImage;
import java.awt.image.RenderedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Set;

import javax.imageio.stream.ImageInputStream;

import org.openslide.OpenSlide;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import edu.illinois.library.cantaloupe.image.Compression;
import edu.illinois.library.cantaloupe.image.Dimension;
import edu.illinois.library.cantaloupe.image.Metadata;
import edu.illinois.library.cantaloupe.image.ScaleConstraint;
import edu.illinois.library.cantaloupe.operation.Crop;
import edu.illinois.library.cantaloupe.operation.ReductionFactor;
import edu.illinois.library.cantaloupe.operation.Scale;
import edu.illinois.library.cantaloupe.processor.codec.BufferedImageSequence;
import edu.illinois.library.cantaloupe.processor.codec.ImageReader;
import edu.illinois.library.cantaloupe.processor.codec.ReaderHint;
import edu.illinois.library.cantaloupe.source.StreamFactory;

public class OpenslideReader  implements ImageReader {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(OpenslideReader.class);
    private OpenSlide openslide = null;
    @Override
    public boolean canSeek() {
        return false;
    }

    @Override
    public void dispose() {
        if (openslide != null) {
            LOGGER.debug("Closing slide image");
            openslide.close();
        }
    }

    @Override
    public Compression getCompression(int imageIndex) throws IOException {
        LOGGER.debug("Returning compression for level {0}", imageIndex);
        AssertSlideOpen();
        return Compression.LZW;
    }

    @Override
    public Metadata getMetadata(int imageIndex) throws IOException {
        LOGGER.debug("Getting metadata");
        AssertSlideOpen();
        final Metadata metadata = new Metadata();
        metadata.setNativeMetadata(openslide.getProperties());
        return metadata;
    }

    @Override
    public int getNumImages() throws IOException {
        AssertSlideOpen();
        int imageCount = 1 + openslide.getAssociatedImages().size();
        return imageCount;
    }

    @Override
    public int getNumResolutions() throws IOException {
        AssertSlideOpen();
        return openslide.getLevelCount();
    }

    @Override
    public Dimension getSize(int imageIndex) throws IOException {
        AssertSlideOpen();
        return new Dimension(openslide.getLevel0Width(), openslide.getLevel0Height());
    }

    @Override
    public Dimension getTileSize(int imageIndex) throws IOException {
        AssertSlideOpen();
        // Just going to return a static side
        return new Dimension(512,512);
    }

    @Override
    public BufferedImage read(int imageIndex) throws IOException {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'read'");
    }

    @Override
    public BufferedImage read(int imageIndex, Crop crop, Scale scale, ScaleConstraint scaleConstraint,
            ReductionFactor reductionFactor, Set<ReaderHint> hints) throws IOException {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'read'");
    }

    @Override
    public RenderedImage readRendered(int imaageIndex, Crop crop, Scale scale, ScaleConstraint scaleConstraint,
            ReductionFactor reductionFactor, Set<ReaderHint> hints) throws IOException {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'readRendered'");
    }

    @Override
    public BufferedImageSequence readSequence() throws IOException {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'readSequence'");
    }

    @Override
    public void setSource(Path imageFile) throws IOException {
        openslide = new OpenSlide(imageFile.toFile());
    }

    @Override
    public void setSource(ImageInputStream inputStream) throws IOException {
        throw new IOException("Unable to use streams");
    }

    @Override
    public void setSource(StreamFactory streamFactory) throws IOException {
        throw new IOException("Unable to use streams");
    }

    private void AssertSlideOpen() throws IOException {
        if (openslide == null) {
            throw new IOException("Slide file is not open");
        }
    }
    
}
