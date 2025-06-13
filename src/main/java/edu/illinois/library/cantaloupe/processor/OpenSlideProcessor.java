package edu.illinois.library.cantaloupe.processor;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;

import org.openslide.OpenSlide;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import edu.illinois.library.cantaloupe.image.Dimension;
import edu.illinois.library.cantaloupe.image.Format;
import edu.illinois.library.cantaloupe.image.Info;
import edu.illinois.library.cantaloupe.image.Metadata;
import edu.illinois.library.cantaloupe.image.Rectangle;
import edu.illinois.library.cantaloupe.image.ScaleConstraint;
import edu.illinois.library.cantaloupe.image.Info.Image;
import edu.illinois.library.cantaloupe.operation.Crop;
import edu.illinois.library.cantaloupe.operation.Encode;
import edu.illinois.library.cantaloupe.operation.OperationList;
import edu.illinois.library.cantaloupe.operation.Scale;
import edu.illinois.library.cantaloupe.operation.ScaleByPercent;
import edu.illinois.library.cantaloupe.operation.ScaleByPixels;
import edu.illinois.library.cantaloupe.processor.codec.ImageWriterFacade;
import edu.illinois.library.cantaloupe.processor.codec.ReaderHint;

public class OpenSlideProcessor extends AbstractProcessor implements FileProcessor {
    private static final Logger LOGGER =
            LoggerFactory.getLogger(OpenSlideProcessor.class);

    private static final String SOURCE_FORMAT = "openslide";
    private OpenSlide openslide;
    private Path sourceFile;    

    @Override
    public void close() {
        // Instead of closing the OpenSlide handle directly, release it to the manager.
        // The manager will decide when to truly close it (e.g., when idle or on shutdown).
        if (this.sourceFile != null) {
            OpenSlideHandleManager.releaseHandle(this.sourceFile);
            this.openslide = null; // Clear local reference
            this.sourceFile = null;
        }
    }

    @Override
    public Set<Format> getAvailableOutputFormats() {
        final HashSet<Format> r = new HashSet<Format>();
        r.add(Format.get("jpg"));
        return r;
    }

    @Override
    public Info readInfo() throws IOException {
        final Info info = Info.builder()
            .withFormat(getSourceFormat())
            .withSize(getSize())
            .withTileSize(getTileSize())
            .withNumResolutions(openslide.getLevelCount())
            .withMetadata(readMetadata())
            .build();
        info.getImages().clear();
        for(var i=0;i<openslide.getLevelCount()-1;i++) {
            final Image image = new Image();            
            final Dimension dimension = new Dimension(openslide.getLevelWidth(i), openslide.getLevelHeight(i));
            image.setSize(dimension);
            image.setTileSize(getTileSize());
            info.getImages().add(image);
        }
        return info;
    }

    @Override
    public boolean supportsSourceFormat(Format format) {
        return Format.get(SOURCE_FORMAT).equals(format);
    }

    @Override
    public Path getSourceFile() {
        return this.sourceFile;
    }

    public void setSourceFormat(Format format) throws SourceFormatException {
        if (!supportsSourceFormat(format)) {
            throw new SourceFormatException(this, format);
        }
    }

    @Override
    public void setSourceFile(Path sourceFile) {
      // Use the OpenSlideHandleManager to get or create the handle
        try {
            if (!OpenSlide.getFileFilter().accept(sourceFile.toFile())) {
                throw new IOException("Unsupported slide format: " + sourceFile);
            }
            // Acquire the OpenSlide handle from the manager
            this.openslide = OpenSlideHandleManager.getOrCreateHandle(sourceFile);
            this.sourceFile = sourceFile; // Store the current source file being handled by this processor instance
        } catch (IOException e) {
            LOGGER.error("Error setting source file and acquiring OpenSlide handle: {}", sourceFile, e);
            throw new IllegalArgumentException("Failed to set OpenSlide source file", e);
        } catch (UncheckedIOException e) {
            LOGGER.error("UncheckedIOException Error setting source file and acquiring OpenSlide handle: {}", sourceFile, e.getCause());
            throw new UncheckedIOException(e.getCause());
        }
    }
    @Override
    public Format getSourceFormat()  {
        return Format.get(SOURCE_FORMAT);
    }

    @Override
    public void process(final OperationList opList,
                        final Info imageInfo,
                        final OutputStream outputStream) throws FormatException, ProcessorException {

        super.process(opList, imageInfo, outputStream);

        try {
            final Dimension fullSize = imageInfo.getSize();
            final ScaleConstraint scaleConstraint = opList.getScaleConstraint();

            // Determine the source region and best OpenSlide level
            final Crop crop = (Crop) opList.getFirst(Crop.class);
            final Rectangle sourceRegion = cropOperation(crop, fullSize, scaleConstraint);
            // Determine the target output size after all scaling operations
            
            final Scale scale = (Scale) opList.getFirst(Scale.class);
            Dimension outputSize = getResultingSize(scale, sourceRegion.size(), scaleConstraint);
            double targetDownsample = (double) sourceRegion.width() / outputSize.width();
            int bestLevel = openslide.getBestLevelForDownsample(targetDownsample);
            Rectangle region = scaleOperation(scale, sourceRegion, bestLevel);

            BufferedImage bi = getRegion(region, bestLevel);

            if (bi == null) {
                throw new ProcessorException("Failed to read image region from OpenSlide");
            }
            postProcessImage(bi, opList, imageInfo, outputStream);
        }       
        catch (IOException e) {
            LOGGER.error("Processing image exception", e);
            throw new ProcessorException(e);
        }
    }

    private void postProcessImage(BufferedImage incomingImage, OperationList opList, Info imageInfo, OutputStream outputStream) throws IOException {
        final Set<ReaderHint> hints =
                EnumSet.of(ReaderHint.ALREADY_CROPPED);
        BufferedImage bi = Java2DPostProcessor.postProcess(incomingImage, hints, opList, imageInfo, null);
        final Encode encode = (Encode) opList.getFirst(Encode.class);
        ImageWriterFacade.write(bi, encode, outputStream);        
    }
    private Dimension getSize() {
        return new Dimension(openslide.getLevel0Width(), openslide.getLevel0Height());
    }

    private BufferedImage getRegion(Rectangle region, int bestLevel) throws IOException {
        return openslide.readRegion(region.intX(), region.intY(), bestLevel, region.intWidth(), region.intHeight());
    }

    private Metadata readMetadata() {
        final Metadata metadata = new Metadata();
        metadata.setNativeMetadata(openslide.getProperties());
        return metadata;
    }

    private Dimension getTileSize() {
        if (openslide == null) {
            return new Dimension(256, 256);
        }
        int w = Integer.parseInt(openslide.getProperties().get("openslide.level[0].tile-width"));
        int h = Integer.parseInt(openslide.getProperties().get("openslide.level[0].tile-height"));

        return new Dimension(w, h);
    }

     /**
     * Calculates the resulting size of an image after applying a scale operation
     * and considering scale constraints.
     *
     * This method essentially re-implements parts of how Cantaloupe determines
     * the final image dimensions.
     *
     * @param scale             The Scale operation to apply (can be null).
     * @param sourceDimension   The dimensions of the source image *after* cropping.
     * @param scaleConstraint   The scale constraint from the OperationList.
     * @return The resulting dimension.
     */
    private Dimension getResultingSize(final Scale scale,
                                       final Dimension sourceDimension,
                                       final ScaleConstraint scaleConstraint) {
        int resultingWidth;
        int resultingHeight;

        if (scale == null) {
            // No scale operation, return the source dimension
            return sourceDimension;
        }

        // Apply scale constraint first
        Dimension constrainedSourceDimension = scaleConstraint.getResultingSize(sourceDimension);

        if (scale instanceof ScaleByPercent) {
            ScaleByPercent sbp = (ScaleByPercent) scale;
            resultingWidth = (int) Math.round(constrainedSourceDimension.width() * (sbp.getPercent() / 100.0));
            resultingHeight = (int) Math.round(constrainedSourceDimension.height() * (sbp.getPercent() / 100.0));
            return new Dimension(Math.max(1, resultingWidth), Math.max(1, resultingHeight));
        } else if (scale instanceof ScaleByPixels) {
            ScaleByPixels sbp = (ScaleByPixels) scale;
            Dimension scaleTo = sbp.getResultingSize(constrainedSourceDimension, scaleConstraint);

            return scaleTo;
        } 

        LOGGER.warn("Unknown Scale operation type: {}", scale.getClass().getName());
        return constrainedSourceDimension;
    }

    private Rectangle cropOperation(Crop crop, Dimension size, ScaleConstraint scaleConstraint) {
        return (crop != null) ?
               crop.getRectangle(size, scaleConstraint) :
               new Rectangle(0,0,size.width(),size.height());
    }

    private Rectangle scaleOperation(Scale scale, Rectangle sourceRegion, int bestLevel) {
        double levelDownsample = openslide.getLevelDownsample(bestLevel);

        // Calculate the region to read in the chosen level's coordinates
        // OpenSlide's readRegion takes level-0 coordinates for x, y
        // but level-specific width/height for the size of the region to read.
        int readX = sourceRegion.intX();
        int readY = sourceRegion.intY();
        int readWidth = (int) (sourceRegion.intWidth() / levelDownsample);
        int readHeight = (int) (sourceRegion.intHeight() / levelDownsample);

        readWidth = Math.min(readWidth,(int)openslide.getLevelWidth(bestLevel) - (int)(readX / levelDownsample));
        readHeight = Math.min(readHeight, (int)openslide.getLevelHeight(bestLevel) - (int)(readY / levelDownsample));

        return new Rectangle(readX, readY, readWidth, readHeight);
    }
}
