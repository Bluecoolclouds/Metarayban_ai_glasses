const { spawn } = require("child_process");

class TwitchRestreamer {
  constructor() {
    this.ffmpegProcess = null;
    this.isStreaming = false;
    this.streamKey = null;
    this.inputFormat = "webm";
    this.bytesReceived = 0;
    this.startTime = null;
    this.lastChunkTime = null;
    this.idleTimer = null;
    this.IDLE_TIMEOUT_MS = 120000;
  }

  start(streamKey, inputFormat = "webm") {
    if (this.isStreaming) {
      return { success: false, message: "Already streaming" };
    }

    this.streamKey = streamKey;
    this.inputFormat = inputFormat;
    this.bytesReceived = 0;
    this.startTime = Date.now();
    this.lastChunkTime = Date.now();

    const rtmpTarget = process.env.RTMP_TARGET;
    const rtmpUrl = rtmpTarget || `rtmp://live.twitch.tv/app/${streamKey}`;

    const commonOutputArgs = [
      "-c:v", "libx264",
      "-preset", "veryfast",
      "-tune", "zerolatency",
      "-crf", "23",
      "-pix_fmt", "yuv420p",
      "-g", "60",
      "-keyint_min", "60",
      "-sc_threshold", "0",
      "-r", "30",
      "-vsync", "cfr",
      "-c:a", "aac",
      "-b:a", "128k",
      "-ar", "44100",
      "-max_muxing_queue_size", "4096",
      "-flvflags", "no_duration_filesize",
      "-f", "flv",
      rtmpUrl,
    ];

    let ffmpegArgs;
    if (inputFormat === "mpegts") {
      ffmpegArgs = [
        "-f", "mpegts",
        "-fflags", "+genpts+discardcorrupt",
        "-analyzeduration", "1000000",
        "-probesize", "2000000",
        "-thread_queue_size", "512",
        "-i", "pipe:0",
        ...commonOutputArgs,
      ];
    } else if (inputFormat === "h264") {
      ffmpegArgs = [
        "-r", "24",
        "-f", "h264",
        "-fflags", "+genpts+discardcorrupt",
        "-thread_queue_size", "512",
        "-i", "pipe:0",
        "-f", "lavfi",
        "-i", "anullsrc=r=44100:cl=stereo",
        "-c:v", "libx264",
        "-preset", "veryfast",
        "-tune", "zerolatency",
        "-crf", "23",
        "-pix_fmt", "yuv420p",
        "-g", "48",
        "-keyint_min", "48",
        "-sc_threshold", "0",
        "-r", "24",
        "-vsync", "cfr",
        "-c:a", "aac",
        "-b:a", "64k",
        "-ar", "44100",
        "-shortest",
        "-max_muxing_queue_size", "1024",
        "-flvflags", "no_duration_filesize",
        "-f", "flv",
        rtmpUrl,
      ];
    } else if (inputFormat === "image2pipe") {
      ffmpegArgs = [
        "-f", "image2pipe",
        "-vcodec", "mjpeg",
        "-framerate", "15",
        "-i", "pipe:0",
        "-f", "lavfi",
        "-i", "anullsrc=r=44100:cl=stereo",
        "-c:v", "libx264",
        "-preset", "veryfast",
        "-tune", "zerolatency",
        "-crf", "23",
        "-pix_fmt", "yuv420p",
        "-g", "30",
        "-keyint_min", "30",
        "-sc_threshold", "0",
        "-r", "15",
        "-vsync", "cfr",
        "-s", "1280x720",
        "-c:a", "aac",
        "-b:a", "64k",
        "-ar", "44100",
        "-shortest",
        "-max_muxing_queue_size", "2048",
        "-flvflags", "no_duration_filesize",
        "-f", "flv",
        rtmpUrl,
      ];
    } else {
      ffmpegArgs = [
        "-re",
        "-f", "webm",
        "-fflags", "+genpts+discardcorrupt",
        "-analyzeduration", "3000000",
        "-probesize", "5000000",
        "-thread_queue_size", "512",
        "-i", "pipe:0",
        ...commonOutputArgs,
      ];
    }

    this.ffmpegProcess = spawn("ffmpeg", ffmpegArgs);

    this.ffmpegProcess.stderr.on("data", (data) => {
      const msg = data.toString();
      if (msg.includes("Error") || msg.includes("error")) {
        console.log(`[Twitch FFmpeg ERROR] ${msg.trim()}`);
      }
    });

    this.ffmpegProcess.on("close", (code) => {
      console.log(`[Twitch] FFmpeg exited with code ${code}`);
      this.isStreaming = false;
      this.ffmpegProcess = null;
      this._clearIdleTimer();
      if (this.onStopped) this.onStopped(code);
    });

    this.ffmpegProcess.on("error", (err) => {
      console.log(`[Twitch] FFmpeg spawn error: ${err.message}`);
      this.isStreaming = false;
      this.ffmpegProcess = null;
      this._clearIdleTimer();
      if (this.onStopped) this.onStopped(-1);
    });

    this._startIdleTimer();

    this.isStreaming = true;
    console.log(`[Twitch] Streaming started`);
    return { success: true, message: "Streaming started" };
  }

  writeChunk(data) {
    if (!this.isStreaming || !this.ffmpegProcess || !this.ffmpegProcess.stdin.writable) {
      return false;
    }
    try {
      this.ffmpegProcess.stdin.write(Buffer.from(data));
      this.bytesReceived += data.byteLength || data.length;
      this.lastChunkTime = Date.now();
      return true;
    } catch (e) {
      console.log(`[Twitch] Write error: ${e.message}`);
      return false;
    }
  }

  stop() {
    if (!this.isStreaming || !this.ffmpegProcess) {
      return { success: false, message: "Not streaming" };
    }

    const duration = Math.round((Date.now() - this.startTime) / 1000);
    const mbReceived = (this.bytesReceived / (1024 * 1024)).toFixed(2);
    console.log(`[Twitch] Stopping stream. Duration: ${duration}s, Data received: ${mbReceived} MB`);

    this._clearIdleTimer();

    try {
      this.ffmpegProcess.stdin.end();
    } catch (e) {}

    setTimeout(() => {
      if (this.ffmpegProcess) {
        this.ffmpegProcess.kill("SIGTERM");
        setTimeout(() => {
          if (this.ffmpegProcess) {
            this.ffmpegProcess.kill("SIGKILL");
          }
        }, 3000);
      }
    }, 2000);

    this.isStreaming = false;
    return { success: true, message: `Stream stopped (${duration}s, ${mbReceived} MB)` };
  }

  getStatus() {
    if (!this.isStreaming) return { streaming: false };
    const duration = Math.round((Date.now() - this.startTime) / 1000);
    const mbReceived = (this.bytesReceived / (1024 * 1024)).toFixed(2);
    return { streaming: true, duration, mbReceived };
  }

  _startIdleTimer() {
    this._clearIdleTimer();
    this.idleTimer = setInterval(() => {
      if (this.lastChunkTime && (Date.now() - this.lastChunkTime > this.IDLE_TIMEOUT_MS)) {
        console.log(`[Twitch] No data received for ${this.IDLE_TIMEOUT_MS / 1000}s, stopping`);
        this.stop();
      }
    }, 5000);
  }

  _clearIdleTimer() {
    if (this.idleTimer) {
      clearInterval(this.idleTimer);
      this.idleTimer = null;
    }
  }
}

module.exports = TwitchRestreamer;
