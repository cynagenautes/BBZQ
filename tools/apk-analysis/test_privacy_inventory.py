import unittest
from privacy_inventory import inventory, compare, signature


class PrivacyInventoryTest(unittest.TestCase):
    def test_descriptor_parser_handles_arrays_and_primitives(self):
        self.assertEqual(signature("La;->m([Ljava/lang/String;I[[B)V"), ("m", ["[Ljava/lang/String;", "I", "[[B"], "V"))

    def test_web_entry_requires_both_operations_and_the_audited_shape(self):
        method = "La;->renamed(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Lcallback;)Ljava/lang/String;"
        index = {"calls": [], "strings": [{"method": method, "value": value} for value in ("getClipboardData", "setClipboardData")]}
        self.assertEqual(inventory(index)["pageReadBoundaries"]["WebDispatch"]["status"], "unique")
        index["strings"].append({"method": method.replace("renamed", "another"), "value": "getClipboardData"})
        index["strings"].append({"method": method.replace("renamed", "another"), "value": "setClipboardData"})
        self.assertEqual(inventory(index)["pageReadBoundaries"]["WebDispatch"]["status"], "ambiguous")

    def test_api_diff_tracks_new_reads_without_offset_only_noise(self):
        edge = {"caller": "La;->m()V", "target": "Landroid/content/ClipboardManager;->getPrimaryClip()Landroid/content/ClipData;", "offset": 2}
        first = inventory({"calls": [edge], "strings": []})
        moved = inventory({"calls": [dict(edge, offset=9)], "strings": []})
        self.assertEqual(compare(first, moved)["addedApiEdges"], [])
        changed = inventory({"calls": [edge, dict(edge, caller="La;->n()V")], "strings": []})
        self.assertEqual(len(compare(first, changed)["addedApiEdges"]), 1)


if __name__ == "__main__":
    unittest.main()
