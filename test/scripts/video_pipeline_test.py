"""Exercise FFmpeg renditions and incremental publication in a disposable catalog."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]


@unittest.skipUnless(shutil.which('ffmpeg') and shutil.which('ffprobe'), 'FFmpeg is required')
class VideoPipeline(unittest.TestCase):
    def test_codecs_freshness_missing_output_force_and_failed_publication(self):
        encoders = subprocess.check_output(['ffmpeg', '-hide_banner', '-encoders'], stderr=subprocess.DEVNULL, text=True)
        if not all(codec in encoders for codec in ['libsvtav1', 'libvpx-vp9', 'libx264']):
            self.skipTest('AV1, VP9 and H.264 encoders are required')
        with tempfile.TemporaryDirectory(prefix='video-pipeline-') as temp:
            root = Path(temp)
            source = root / 'resources/public/media/clip.mp4'
            source.parent.mkdir(parents=True)
            subprocess.run(['ffmpeg', '-hide_banner', '-loglevel', 'error', '-f', 'lavfi',
                '-i', 'color=c=red:s=256x144:r=10:d=0.2', '-c:v', 'libx264',
                '-pix_fmt', 'yuv420p', str(source)], check=True)
            (root / 'resources/responsive-videos.edn').write_text(
                '{"media/clip.mp4" {:width 128 :media "(max-width: 600px)"}}')

            def run(*args):
                expression = '(require \'[media.videos :as v]) (v/responsive! ' + ' '.join(map(json.dumps, args)) + ')'
                return subprocess.run(['bb', '--classpath', str(ROOT / 'scripts'), '-e', expression],
                    cwd=root, capture_output=True, text=True)

            outputs = {codec: source.with_name('clip-128w' + suffix) for codec, suffix in
                [('av1', '-av1.webm'), ('vp9', '-vp9.webm'), ('h264', '.mp4')]}
            first = run()
            self.assertEqual(first.returncode, 0, first.stderr)
            for codec, file in outputs.items():
                probe = json.loads(subprocess.check_output(['ffprobe', '-v', 'error',
                    '-show_streams', '-of', 'json', str(file)], text=True))['streams']
                self.assertEqual([(s['codec_name'], s.get('width'), s.get('height')) for s in probe],
                    [(codec, 128, 72)])
            times = lambda: {codec: file.stat().st_mtime_ns for codec, file in outputs.items()}
            before = times()
            self.assertEqual(run().returncode, 0)
            self.assertEqual(times(), before)
            outputs['vp9'].unlink()
            self.assertEqual(run().returncode, 0)
            self.assertGreater(times()['vp9'], before['vp9'])
            self.assertEqual(times()['av1'], before['av1'])
            self.assertEqual(times()['h264'], before['h264'])
            before = times()
            self.assertEqual(run('--force').returncode, 0)
            self.assertTrue(all(times()[codec] > before[codec] for codec in outputs))
            os.utime(source, None)
            # Filesystem/Java timestamps may round to milliseconds. Make the
            # stale-output condition explicit without a timing-dependent sleep.
            for file in outputs.values():
                older = source.stat().st_mtime_ns - 2_000_000_000
                os.utime(file, ns=(older, older))
            before = times()
            self.assertEqual(run().returncode, 0)
            self.assertTrue(all(times()[codec] > before[codec] for codec in outputs))
            saved = {codec: file.read_bytes() for codec, file in outputs.items()}
            source.write_bytes(b'invalid video')
            self.assertNotEqual(run('--force').returncode, 0)
            self.assertEqual({codec: file.read_bytes() for codec, file in outputs.items()}, saved)
            self.assertEqual(list(source.parent.glob('responsive-*')), [])


if __name__ == '__main__':
    unittest.main()
