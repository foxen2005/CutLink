import pytest

from bridge.gpgl import (CutSettings, RegmarkSettings, mm, setup_commands,
                         plot_commands, end_commands, regmark_search_command)


def test_mm_to_su():
    assert mm(1) == 20
    assert mm(50) == 1000
    assert mm(0.05) == 1
    assert mm(0.024) == 0   # bajo la resolución


def test_setup_uses_toolholder_and_ranges():
    s = CutSettings(tool=1, speed=10, pressure=12, depth=3, mat=1)
    cmds = setup_commands(s)
    assert cmds[0] == "TG1"
    assert "J1" in cmds
    assert "!10,1" in cmds
    assert cmds.count("FX12,1") == 2
    assert cmds[-1] == "TF3,1"


def test_setup_without_depth_has_no_TF():
    assert not any(c.startswith("TF") for c in setup_commands(CutSettings(depth=None)))


@pytest.mark.parametrize("bad", [
    dict(speed=0), dict(speed=31), dict(pressure=34), dict(depth=11), dict(tool=3), dict(mat=7), dict(passes=0),
])
def test_validate_rejects(bad):
    with pytest.raises(ValueError):
        CutSettings(**bad).validate()


def test_plot_square_swaps_xy_and_clips():
    sq = [(10.0, 20.0), (60.0, 20.0), (60.0, 70.0), (10.0, 70.0), (10.0, 20.0)]
    cmds = plot_commands([sq], (10, 20, 60, 70))
    assert cmds[0] == "\\400,200"        # y,x del mínimo
    assert cmds[1] == "Z1400,1200"
    assert cmds[5] == "M400,200"         # primer punto: pen up
    assert cmds[6] == "D400,1200"        # (x=60,y=20) -> y,x
    assert cmds[-1] == "D400,200"


def test_plot_passes_repeat_paths():
    line = [(0.0, 0.0), (10.0, 0.0)]
    one = plot_commands([line], (0, 0, 10, 0), passes=1)
    two = plot_commands([line], (0, 0, 10, 0), passes=2)
    assert len(two) - len(one) == 2


def test_plot_offset_shifts_to_regmark_origin():
    line = [(15.0, 15.0), (25.0, 15.0)]
    cmds = plot_commands([line], (15, 15, 25, 15), offset_mm=(10.0, 10.0))
    assert "M100,100" in cmds
    assert "D100,300" in cmds


def test_end_moves_past_bbox_and_resets():
    cmds = end_commands((0, 0, 50, 50))
    assert cmds == ["M1200,0", "SO0", "FN0"]


def test_regmark_search_command_geometry():
    r = RegmarkSettings(enabled=True, inset_mm=10, page_w_mm=210, page_h_mm=297)
    assert regmark_search_command(r) == "TB123,5540,3800,200,200"
