import datetime as dt
import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location('cleanup', Path(__file__).resolve().parents[2] / 'scripts/ops/host/coolify-storage-cleanup.py')
cleanup = importlib.util.module_from_spec(spec)
spec.loader.exec_module(cleanup)
NOW = dt.datetime(2026, 10, 15, tzinfo=dt.timezone.utc)
REPO = 'bsok8o8csgso8c00g00k0csg'


def image(number, age, repository=REPO):
    return {'Id': str(number), 'Created': (NOW - dt.timedelta(days=age)).isoformat(),
            'RepoTags': [repository + ':' + format(number, '040x')]}


class CleanupTest(unittest.TestCase):
    def test_preserves_three_rollbacks_recent_images_and_all_container_references(self):
        images = [image(i, i) for i in range(1, 11)]
        tags = cleanup.candidates(images, [{'Image': '9'}], NOW)
        self.assertEqual(tags, [images[i - 1]['RepoTags'][0] for i in [4, 5, 6, 7, 8, 10]])
        self.assertEqual(cleanup.candidates([image(i, i / 24) for i in range(1, 11)], [], NOW), [])

    def test_preserves_unrelated_and_mutable_tags(self):
        self.assertFalse(cleanup.managed('postgres:15'))
        self.assertFalse(cleanup.managed('127.0.0.1:5005/tolgraven/strapi:latest'))
        self.assertTrue(cleanup.managed('o84wgo08wcs048ss8sokgkgw:pr-193-' + 'a' * 40))
        self.assertTrue(cleanup.managed('127.0.0.1:5005/tolgraven/strapi:451be77a65aa-20261015203145'))
        self.assertEqual(cleanup.candidates([image(i, 20, 'postgres') for i in range(5)], [], NOW), [])

    def test_duplicate_tags_do_not_consume_rollback_slots(self):
        images = [image(i, 30 + i) for i in range(1, 5)]
        images[0]['RepoTags'] += [REPO + ':pr-193-' + 'f' * 40]
        self.assertEqual(cleanup.candidates(images, [], NOW), [images[3]['RepoTags'][0]])

    def test_stopped_container_also_protects_its_image(self):
        images = [image(i, 30 + i) for i in range(1, 5)]
        self.assertEqual(cleanup.candidates(images, [{'Image': '4', 'State': 'exited'}], NOW), [])


if __name__ == '__main__':
    unittest.main()
