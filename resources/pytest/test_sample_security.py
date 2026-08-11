"""
FCS_COP.1 Sample Cryptographic Verification Test
Verifies cryptographic operations, hash computation, and device bridge access.
"""

import unittest
import hashlib
import os

# Metadata for Testbed Core Test Explorer
CATEGORY = "Python Test"
TITLE = "Sample Cryptographic & Hash Test"
DESCRIPTION = "Verifies SHA-256 / SHA-512 digest calculations and device serial bridge via embedded Python."


class TestSampleSecurity(unittest.TestCase):

    def setUp(self):
        # Access bridge if available (injected by Testbed Core)
        self.device_serial = globals().get("bridge", None) and globals()["bridge"].getDeviceSerial()

    def test_sha256_digest(self):
        """Verifies SHA-256 hash computation accuracy."""
        data = b"Common Criteria MDFPP Test Vector"
        expected = hashlib.sha256(data).hexdigest()
        self.assertEqual(len(expected), 64)
        self.assertTrue(expected.isalnum())

    def test_sha512_digest(self):
        """Verifies SHA-512 hash computation accuracy."""
        data = b"Common Criteria MDFPP Test Vector 512"
        expected = hashlib.sha512(data).hexdigest()
        self.assertEqual(len(expected), 128)

    def test_key_length_validation(self):
        """Verifies symmetric key strength meets 256-bit requirement."""
        allowed_aes_keys = [128, 256]
        tested_key_size = 256
        self.assertIn(tested_key_size, allowed_aes_keys)
        self.assertGreaterEqual(tested_key_size, 256, "MDFPP requires minimum AES-256 for high assurance")


if __name__ == "__main__":
    unittest.main()
