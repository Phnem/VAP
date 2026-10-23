"""Give a locally supplied test font a unique family name; preserve its copyright/license records."""
from pathlib import Path
import sys
from fontTools.ttLib import TTFont

source, destination = map(Path, sys.argv[1:3])
font = TTFont(source)
names = {1: 'VAPFixture', 2: 'Regular', 3: 'VAPFixture-Regular', 4: 'VAPFixture', 6: 'VAPFixture-Regular', 16: 'VAPFixture', 17: 'Regular'}
for record in font['name'].names:
    if record.nameID in names:
        record.string = names[record.nameID].encode(record.getEncoding())
font.save(destination)
