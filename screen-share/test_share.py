import unittest
from share import command


class CommandTest(unittest.TestCase):
    def test_screen_uses_main_display(self):
        self.assertEqual(command(), ['scrcpy', '--window-title=OpenStream Screen',
                         '--max-size=1920', '--max-fps=30', '--video-bit-rate=8M'])

    def test_app_and_device_are_separate_arguments(self):
        self.assertEqual(command('com.example.app', 'device 1')[-4:],
                         ['--serial', 'device 1', '--new-display=1080x1920',
                          '--start-app=com.example.app'])


if __name__ == '__main__':
    unittest.main()
