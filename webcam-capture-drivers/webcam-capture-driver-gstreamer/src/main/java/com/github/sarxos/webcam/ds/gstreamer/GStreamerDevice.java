package com.github.sarxos.webcam.ds.gstreamer;

import java.awt.Dimension;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.gstreamer.Caps;
import org.gstreamer.Element;
import org.gstreamer.ElementFactory;
import org.gstreamer.Pad;
import org.gstreamer.Pipeline;
import org.gstreamer.State;
import org.gstreamer.Structure;
import org.gstreamer.elements.RGBDataSink;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.sarxos.webcam.WebcamDevice;
import com.github.sarxos.webcam.WebcamResolution;


public class GStreamerDevice implements WebcamDevice, RGBDataSink.Listener, WebcamDevice.FPSSource {

	private static final Logger LOG = LoggerFactory.getLogger(GStreamerDevice.class);

	/**
	 * Limit the lateness of frames to no more than 20ms (half a frame at 25fps)
	 */
	private static final long LATENESS = 20; // ms

	/**
	 * Video format to capture.
	 */
	private static final String FORMAT = "video/x-raw-yuv";

	/**
	 * All possible resolutions - populated while initialization phase.
	 */
	private Dimension[] resolutions = null;

	/**
	 * Device name, immutable.
	 */
	private final String name;

	/* gstreamer stuff */

	private Pipeline pipe = null;
	private Element source = null;
	private Element filter = null;
	private RGBDataSink sink = null;
	private Caps caps = null;

	/* logic */

	private AtomicBoolean open = new AtomicBoolean(false);
	private AtomicBoolean disposed = new AtomicBoolean(false);
	private AtomicBoolean starting = new AtomicBoolean(false);
	private AtomicBoolean initialized = new AtomicBoolean(false);
	private Dimension resolution = WebcamResolution.VGA.getSize();
	private BufferedImage image = null;

	/* used to calculate fps */

	private long t1 = -1;
	private long t2 = -1;

	private volatile double fps = 0;

	/**
	 * Create GStreamer webcam device.
	 * 
	 * @param name the name of webcam device
	 */
	protected GStreamerDevice(String name) {
		this.name = name;
	}

	/**
	 * Initialize webcam device.
	 */
	private synchronized void init() {

		if (!initialized.compareAndSet(false, true)) {
			return;
		}

		LOG.debug("GStreamer webcam device initialization");

		pipe = new Pipeline(name);

		source = ElementFactory.make("dshowvideosrc", "source");
		source.set("device-name", name);

		sink = new RGBDataSink(name, this);
		sink.setPassDirectBuffer(true);
		sink.getSinkElement().setMaximumLateness(LATENESS, TimeUnit.MILLISECONDS);
		sink.getSinkElement().setQOSEnabled(true);

		filter = ElementFactory.make("capsfilter", "filter");

		resolutions = parseResolutions(source.getPads().get(0));
	}

	/**
	 * Use GStreamer to get all possible resolutions.
	 * 
	 * @param pad the pad to get resolutions from
	 * @return Array of resolutions supported by device connected with pad
	 */
	private static final Dimension[] parseResolutions(Pad pad) {

		List<Dimension> dimensions = new ArrayList<Dimension>();

		Caps caps = pad.getCaps();

		Structure structure = null;
		String format = null;

		int n = caps.size();
		int i = 0;

		int w = -1;
		int h = -1;

		do {

			structure = caps.getStructure(i++);

			LOG.debug("Found format structure {}", structure);

			format = structure.getName();

			if (format.equals(FORMAT)) {
				w = structure.getRange("width").getMinInt();
				h = structure.getRange("height").getMinInt();
				dimensions.add(new Dimension(w, h));
			}

		} while (i < n);

		return dimensions.toArray(new Dimension[dimensions.size()]);
	}

	@Override
	public String getName() {
		return name;
	}

	@Override
	public Dimension[] getResolutions() {
		init();
		return resolutions;
	}

	@Override
	public Dimension getResolution() {
		return resolution;
	}

	@Override
	public void setResolution(Dimension size) {
		this.resolution = size;
	}

	@Override
	public BufferedImage getImage() {
		return image;
	}

	@Override
	public void open() {

		if (!open.compareAndSet(false, true)) {
			return;
		}

		LOG.debug("Opening GStreamer device");

		init();

		starting.set(true);

		Dimension size = getResolution();

		image = new BufferedImage(size.width, size.height, BufferedImage.TYPE_INT_RGB);
		image.setAccelerationPriority(0);
		image.flush();

		if (caps != null) {
			caps.dispose();
		}

		caps = Caps.fromString(String.format("%s,width=%d,height=%d", FORMAT, size.width, size.height));

		filter.setCaps(caps);

		LOG.debug("Link elements");

		pipe.addMany(source, filter, sink);
		Element.linkMany(source, filter, sink);
		pipe.setState(State.PLAYING);

		// wait max 20s for image to appear
		synchronized (this) {
			LOG.debug("Wait for device to be ready");
			try {
				this.wait(20000);
			} catch (InterruptedException e) {
				return;
			}
		}
	}

	@Override
	public void close() {

		if (!open.compareAndSet(true, false)) {
			return;
		}

		LOG.debug("Closing GStreamer device");

		image = null;

		LOG.debug("Unlink elements");

		pipe.setState(State.NULL);
		Element.unlinkMany(source, filter, sink);
		pipe.removeMany(source, filter, sink);
	}

	@Override
	public void dispose() {

		if (!disposed.compareAndSet(false, true)) {
			return;
		}

		LOG.debug("Disposing GStreamer device");

		close();

		filter.dispose();
		source.dispose();
		sink.dispose();
		pipe.dispose();
		caps.dispose();
	}

	@Override
	public boolean isOpen() {
		return open.get();
	}

	@Override
	public void rgbFrame(boolean preroll, int width, int height, IntBuffer rgb) {

		LOG.trace("New RGB frame");

		if (t1 == -1 || t2 == -1) {
			t1 = System.currentTimeMillis();
			t2 = System.currentTimeMillis();
		}

		BufferedImage tmp = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
		tmp.setAccelerationPriority(0);
		tmp.flush();

		rgb.get(((DataBufferInt) tmp.getRaster().getDataBuffer()).getData(), 0, width * height);

		image = tmp;

		if (starting.compareAndSet(true, false)) {

			synchronized (this) {
				this.notifyAll();
			}

			LOG.debug("GStreamer device ready");
		}

		t1 = t2;
		t2 = System.currentTimeMillis();

		fps = (4 * fps + 1000 / (t2 - t1 + 1)) / 5;
	}

	@Override
	public double getFPS() {
		return fps;
	}
}