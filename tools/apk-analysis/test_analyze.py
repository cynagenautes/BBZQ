import copy
import unittest
from analyze import compare


class ClipboardComparisonTest(unittest.TestCase):
    def setUp(self):
        self.baseline = {
            "apkSha256": "old-apk",
            "identity": {"package": "tv.danmaku.bili", "versionName": "9.0.0"},
            "readHelpers": [{"method": "Checker->a(ClipboardManager)String", "smaliBodySha256": "old-body"}],
            "frameworkReads": [{"caller": "Checker->a(ClipboardManager)String", "target": "getPrimaryClip", "offset": 4}],
        }

    def test_renamed_helper_is_reported_as_added_and_removed(self):
        current = copy.deepcopy(self.baseline)
        current["readHelpers"][0]["method"] = "Checker->z(ClipboardManager)String"
        diff = compare(self.baseline, current)
        self.assertEqual(["Checker->a(ClipboardManager)String"], diff["removedReadHelpers"])
        self.assertEqual(["Checker->z(ClipboardManager)String"], diff["addedReadHelpers"])

    def test_same_descriptor_with_changed_body_requires_reinspection(self):
        current = copy.deepcopy(self.baseline)
        current["readHelpers"][0]["smaliBodySha256"] = "new-body"
        diff = compare(self.baseline, current)
        self.assertEqual(["Checker->a(ClipboardManager)String"], diff["changedReadHelperBodies"])
        self.assertEqual([], diff["addedReadHelpers"])

    def test_offset_only_changes_do_not_imply_new_callers(self):
        current = copy.deepcopy(self.baseline)
        current["frameworkReads"][0]["offset"] = 90
        diff = compare(self.baseline, current)
        self.assertEqual([], diff["addedFrameworkReadCallers"])
        self.assertEqual([], diff["removedFrameworkReadCallers"])


if __name__ == "__main__":
    unittest.main()
