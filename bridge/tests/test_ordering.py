from bridge.ordering import order_paths, depth_of_each
from bridge.svgpaths import Polyline


def square(x, y, s, name):
    pts = [(x, y), (x + s, y), (x + s, y + s), (x, y + s), (x, y)]
    return Polyline(points=pts, closed=True, stroke=None, fill=None, id=name, group_ids=[])


def test_inner_shapes_cut_before_outer():
    outer = square(0, 0, 100, "outer")
    hole = square(40, 40, 20, "hole")
    inner_most = square(45, 45, 5, "innermost")
    far = square(200, 0, 10, "far")   # exterior independiente
    ordered = order_paths([outer, far, hole, inner_most])
    ids = [p.id for p in ordered]
    assert ids.index("innermost") < ids.index("hole") < ids.index("outer")
    assert depth_of_each([outer, hole, inner_most]) == [0, 1, 2]


def test_nearest_neighbour_within_level():
    a = square(0, 0, 10, "a")
    b = square(300, 0, 10, "b")
    c = square(20, 0, 10, "c")
    ids = [p.id for p in order_paths([b, a, c])]
    assert ids == ["a", "c", "b"]   # arranca en el origen y va al más cercano


def test_open_path_never_contains():
    line = Polyline(points=[(0, 0), (100, 0)], closed=False, stroke=None, fill=None, id="l", group_ids=[])
    inner = square(10, 10, 5, "s")
    assert depth_of_each([line, inner]) == [0, 0]
