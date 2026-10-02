package main

import (
	"context"
	"errors"
	"fmt"
	"net/http"

	"github.com/JacobHayes/bootleg/remote/protocol"
)

// Exit codes (ARCHITECTURE.md "CLI").
const (
	exitOK      = 0
	exitFailure = 1
	exitUsage   = 2
	exitAuth    = 3
	exitLease   = 4
	exitDevice  = 5
	exitTimeout = 6
)

type usageError struct{ message string }

func (e *usageError) Error() string { return e.message }

func usageErrorf(format string, args ...any) error {
	return &usageError{fmt.Sprintf(format, args...)}
}

// deviceError means the phone couldn't be reached or couldn't do it.
type deviceError struct{ err error }

func (e *deviceError) Error() string { return e.err.Error() }
func (e *deviceError) Unwrap() error { return e.err }

type httpStatusError struct{ status int }

func (e *httpStatusError) Error() string {
	switch e.status {
	case http.StatusUnauthorized:
		return "not paired: run `bootleg pair <code>` with the code shown on the phone"
	case http.StatusForbidden:
		return "the phone refused this connection (not a tailnet peer it can identify, or a cross-origin request)"
	case http.StatusServiceUnavailable:
		return "the phone has too many remote connections right now"
	}
	return fmt.Sprintf("the phone answered HTTP %d", e.status)
}

type protocolError struct{ body protocol.ErrorBody }

func (e *protocolError) Error() string {
	if e.body.Message != "" {
		return fmt.Sprintf("%s: %s", e.body.Code, e.body.Message)
	}
	return e.body.Code
}

type closeError struct {
	code   int
	reason string
	err    error
}

func (e *closeError) Error() string {
	if e.reason != "" {
		return fmt.Sprintf("the phone closed the session: %s", e.reason)
	}
	if e.code > 0 {
		return fmt.Sprintf("the phone closed the session (code %d)", e.code)
	}
	return fmt.Sprintf("connection lost: %v", e.err)
}

var errPending = errors.New("this computer is waiting for approval: open Bootleg on the phone and approve it (or rerun with --wait)")

func exitCodeFor(err error) int {
	if err == nil {
		return exitOK
	}
	var usage *usageError
	var device *deviceError
	var status *httpStatusError
	var failure *protocolError
	var closed *closeError
	switch {
	case errors.As(err, &usage):
		return exitUsage
	case errors.Is(err, errPending):
		return exitAuth
	case errors.Is(err, context.DeadlineExceeded):
		return exitTimeout
	case errors.As(err, &failure):
		switch failure.body.Code {
		case protocol.ErrorNotAuthorized, protocol.ErrorPending, protocol.ErrorUnauthorized, protocol.ErrorRevoked:
			return exitAuth
		case protocol.ErrorLeaseRequired, protocol.ErrorLeaseHeld, protocol.ErrorStaleEpoch, protocol.ErrorLocalPriority,
			protocol.ErrorLeaseChanged, protocol.ErrorDeclined:
			return exitLease
		case protocol.ErrorTimeout:
			return exitTimeout
		case protocol.ErrorBadRequest:
			return exitUsage
		}
		return exitDevice
	case errors.As(err, &status):
		if status.status == http.StatusUnauthorized || status.status == http.StatusForbidden {
			return exitAuth
		}
		return exitDevice
	case errors.As(err, &closed):
		if closed.code == protocol.CloseUnauthorized {
			return exitAuth
		}
		return exitDevice
	case errors.As(err, &device):
		return exitDevice
	}
	return exitFailure
}

// leaseError is a lease conflict found by the CLI itself.
func leaseError(format string, args ...any) error {
	return &protocolError{protocol.ErrorBody{Code: protocol.ErrorLeaseHeld, Message: fmt.Sprintf(format, args...)}}
}
