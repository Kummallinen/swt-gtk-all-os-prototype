package org.eclipse.swt.widgets;

import java.util.function.*;

/**
 * OS-independent utility methods defined on controls.
 *
 * @since 3.125
 */
public final class ControlUtil {

	private ControlUtil() {
	}

	/**
	 * Executes the given {@code executable} while redrawing is disabled on the given {@link Control}.
	 * It is ensured that in case the {@code executable} throws any exception, redrawing will still be re-enabled.
	 *
	 * @param <T> the return type of the execute and this method
	 * @param control the control on which redrawing shall be disabled during execution
	 * @param executable the runnable to execute
	 */
	public static <T> T executeWithRedrawDisabled(Control control, Supplier<T> executable) {
		control.setRedraw(false);
		try {
			return executable.get();
		} finally {
			control.setRedraw(true);
		}
	}

	/**
	 * Executes the given {@code executable} while redrawing is disabled on the given {@link Control}.
	 * It is ensured that in case the {@code executable} throws any exception, redrawing will still be re-enabled.
	 *
	 * @param control the control on which redrawing shall be disabled during execution
	 * @param executable the runnable to execute
	 */
	public static void executeWithRedrawDisabled(Control control, Runnable executable) {
		control.setRedraw(false);
		try {
			executable.run();
		} finally {
			control.setRedraw(true);
		}
	}

}
