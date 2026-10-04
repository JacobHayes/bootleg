package main

import (
	"testing"

	"github.com/JacobHayes/bootleg/remote/protocol"
)

func TestHidStatus(t *testing.T) {
	cases := []struct {
		hid  protocol.HidState
		want string
	}{
		{protocol.HidState{Link: "open"}, ""},
		{protocol.HidState{Link: "reconnecting"}, "reconnecting"},
		{protocol.HidState{Link: "open", Status: "release_pending"}, "release_pending"},
		{protocol.HidState{Link: "open", NotReading: []string{"mouse"}}, "the computer isn't reading the mouse"},
		{protocol.HidState{Link: "open", NotReading: []string{"keyboard", "mouse"}}, "the computer isn't reading the keyboard or mouse"},
	}
	for _, c := range cases {
		if got := hidStatus(c.hid); got != c.want {
			t.Errorf("hidStatus(%+v) = %q, want %q", c.hid, got, c.want)
		}
	}
}
