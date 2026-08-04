package com.gouzhu.widget;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;

import androidx.appcompat.widget.AppCompatButton;

import com.gouzhu.transaction.TransactionOccupancyManager;

/**
 * Final UI safety guard for the QR cancellation action.
 *
 * <p>Even if an older Activity callback tries to show or enable this button, cancellation is
 * never exposed after the transaction has entered physical dispense, finishing, refund or
 * blocked phases.</p>
 */
public final class SafePaymentCancelButton extends AppCompatButton {

    public SafePaymentCancelButton(Context context) {
        super(context);
    }

    public SafePaymentCancelButton(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public SafePaymentCancelButton(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    @Override
    public void setVisibility(int visibility) {
        super.setVisibility(visibility == View.VISIBLE && !canShow()
                ? View.GONE
                : visibility);
    }

    @Override
    public void setEnabled(boolean enabled) {
        super.setEnabled(enabled && canExecute());
    }

    private boolean canShow() {
        TransactionOccupancyManager.Snapshot snapshot =
                TransactionOccupancyManager.get(getContext()).current();
        if (snapshot == null
                || !TransactionOccupancyManager.OWNER_QR_PURCHASE.equals(
                snapshot.ownerType)) {
            return false;
        }
        String phase = snapshot.phase;
        return TransactionOccupancyManager.PHASE_PREPARING.equals(phase)
                || TransactionOccupancyManager.PHASE_WAITING_PAYMENT.equals(phase)
                || TransactionOccupancyManager.PHASE_CANCELLING.equals(phase)
                || TransactionOccupancyManager.PHASE_CONFIRMING_CLOSE.equals(phase);
    }

    private boolean canExecute() {
        TransactionOccupancyManager.Snapshot snapshot =
                TransactionOccupancyManager.get(getContext()).current();
        if (snapshot == null
                || !TransactionOccupancyManager.OWNER_QR_PURCHASE.equals(
                snapshot.ownerType)) {
            return false;
        }
        String phase = snapshot.phase;
        return TransactionOccupancyManager.PHASE_PREPARING.equals(phase)
                || TransactionOccupancyManager.PHASE_WAITING_PAYMENT.equals(phase)
                || TransactionOccupancyManager.PHASE_CONFIRMING_CLOSE.equals(phase);
    }
}
